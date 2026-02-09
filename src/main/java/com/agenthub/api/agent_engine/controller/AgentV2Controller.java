package com.agenthub.api.agent_engine.controller;

import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.model.AgentChatRequest;
import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.agenthub.api.knowledge.service.IChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/v2/agent")
@RequiredArgsConstructor
public class AgentV2Controller {

    private final ChatAgent chatAgent;
    private final IChatHistoryService chatHistoryService;
    private final IChatSessionService chatSessionService;
    private final Executor taskExecutor;
    private final Executor sseExecutor;  // 由 Spring 按名称注入 sseExecutor Bean

    @CrossOrigin(origins = "*", maxAge = 3600)
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5分钟超时

        String sessionId = StringUtils.hasText(request.getSessionId()) ? request.getSessionId() : UUID.randomUUID().toString();
        Long userIdLong = request.getUserId() != null ? Long.parseLong(request.getUserId()) : 0L;
        String query = request.getQuery();

        // 添加 completion 和 error 回调，确保 emitter 正常关闭
        final String finalSessionId = sessionId;
        emitter.onCompletion(() -> {
            log.info("[SSE] Emitter completed for session={}", finalSessionId);
        });
        emitter.onTimeout(() -> {
            log.warn("[SSE] Emitter timeout for session={}", finalSessionId);
            emitter.complete();
        });
        emitter.onError((e) -> {
            log.error("[SSE] Emitter error for session=" + finalSessionId, e);
        });

        // 关键点：在 Tomcat 主线程捕获 SecurityContext
        SecurityContext mainThreadSecurityContext = SecurityContextHolder.getContext();

        log.info("接收 V2 Agent 请求: user={}, session={}", userIdLong, sessionId);

        // ===== 骨架创建用通用线程池异步执行，不阻塞主线程 =====
        final Long[] assistantIdHolder = {null};
        taskExecutor.execute(() -> {
            SecurityContextHolder.setContext(mainThreadSecurityContext);
            try {
                Long id = chatHistoryService.createChatSkeleton(sessionId, userIdLong, query);
                assistantIdHolder[0] = id;
                log.info("[骨架创建] 异步完成: session={}, id={}", sessionId, id);
            } catch (Exception e) {
                log.error("[骨架创建] 失败: session={}", sessionId, e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        AgentContext context = AgentContext.builder()
                .query(query)
                .userId(String.valueOf(userIdLong))
                .sessionId(sessionId)
                .docContent(request.getDocContent())
                .build();

        // 异步执行
        sseExecutor.execute(() -> {
            SecurityContextHolder.setContext(mainThreadSecurityContext);
            
            // 分离缓冲区，分别收集思考过程和正文 (用于持久化)
            StringBuilder reasoningBuffer = new StringBuilder();
            StringBuilder contentBuffer = new StringBuilder();
            
            // 状态机：标记当前是否处于思考模式
            final boolean[] isThinking = {false};
            
            try {
                chatAgent.stream(context)
                    .doOnNext(chunk -> {
                        try {
                            String data = chunk;
                            
                            // 1. 处理工具调用
                            if (data.startsWith("__TOOL_CALL__:")) {
                                String content = data.substring(14);
                                // 工具调用也记录在思考过程中，方便回溯
                                reasoningBuffer.append("\n[调用工具: ").append(content).append("]\n");
                                emitter.send(SseEmitter.event().name("tool").data(content));
                                return;
                            }

                            // 2. 处理思考开始标记
                            if (data.contains("@@THINK_START@@")) {
                                isThinking[0] = true;
                                data = data.replace("@@THINK_START@@", "");
                            }

                            // 3. 处理思考结束标记
                            if (data.contains("@@THINK_END@@")) {
                                isThinking[0] = false;
                                data = data.replace("@@THINK_END@@", "");
                            }

                            if (data.isEmpty()) {
                                return;
                            }

                            // 转义处理 (防止 SSE 断裂)
                            // 关键：将换行符替换为 \n，前端再还原回来
                            String safeData = data.replace("\n", "\\n").replace("\r", "\\r");

                            // 4. 根据状态分发并收集
                            if (isThinking[0]) {
                                reasoningBuffer.append(data);
                                emitter.send(SseEmitter.event().name("thinking").data(safeData));
                            } else {
                                contentBuffer.append(data);
                                emitter.send(SseEmitter.event().data(safeData));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Client disconnected", e);
                        }
                    })
                    .doOnComplete(() -> {
                        // 构建最终完整回答 (包含 <think> 标签，用于持久化)
                        StringBuilder finalAnswer = new StringBuilder();
                        if (reasoningBuffer.length() > 0) {
                            finalAnswer.append("<think>").append(reasoningBuffer).append("</think>\n\n");
                        }
                        finalAnswer.append(contentBuffer);
                        
                        handleComplete(sessionId, userIdLong, query, assistantIdHolder[0], finalAnswer.toString());
                        emitter.complete();
                    })
                    .doOnError(e -> {
                        // 出错时尽可能保存已生成的内容
                        StringBuilder partialAnswer = new StringBuilder();
                        if (reasoningBuffer.length() > 0) {
                            partialAnswer.append("<think>").append(reasoningBuffer).append("</think>\n\n");
                        }
                        partialAnswer.append(contentBuffer);
                        
                        handleError(sessionId, assistantIdHolder[0], partialAnswer.toString(), e);
                        emitter.completeWithError(e);
                    })
                    .subscribe();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        
        return emitter;
    }

    private void handleComplete(String sessionId, Long userId, String query, Long assistantId, String answer) {
        try {
            if (!answer.isEmpty()) {
                if (assistantId != null) {
                    chatHistoryService.updateAnswer(assistantId, answer, "completed");
                } else {
                    chatHistoryService.saveChat(sessionId, userId, query, answer);
                }
                chatSessionService.updateSession(sessionId, userId, query);
                log.info("V2 会话保存成功: session={}", sessionId);
            }
        } catch (Exception e) {
            log.error("V2 会话保存异常", e);
        }
    }

    private void handleError(String sessionId, Long assistantId, String partialAnswer, Throwable e) {
        log.warn("V2 异常: session={}, error={}", sessionId, e.getMessage());
        if (assistantId != null) {
            chatHistoryService.markAsInterrupted(assistantId, partialAnswer, e.getMessage());
        }
    }
}