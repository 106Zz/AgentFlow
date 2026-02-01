package com.agenthub.api.agent_engine.controller;

import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.model.AgentChatRequest;
import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.agenthub.api.knowledge.service.IChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/v2/agent")
@RequiredArgsConstructor
public class AgentV2Controller {

    private final ChatAgent chatAgent;
    private final IChatHistoryService chatHistoryService;
    private final IChatSessionService chatSessionService;
    
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        
        String sessionId = StringUtils.hasText(request.getSessionId()) ? request.getSessionId() : UUID.randomUUID().toString();
        Long userIdLong = request.getUserId() != null ? Long.parseLong(request.getUserId()) : 0L;
        String query = request.getQuery();

        // 关键点：在 Tomcat 主线程捕获 SecurityContext
        SecurityContext mainThreadSecurityContext = SecurityContextHolder.getContext();

        log.info("接收 V2 Agent 请求: user={}, session={}", userIdLong, sessionId);

        Long assistantIdVal = null;
        try {
            assistantIdVal = chatHistoryService.createChatSkeleton(sessionId, userIdLong, query);
        } catch (Exception e) {
            log.error("骨架创建失败", e);
        }
        final Long assistantId = assistantIdVal;

        AgentContext context = AgentContext.builder()
                .query(query)
                .userId(String.valueOf(userIdLong))
                .sessionId(sessionId)
                .docContent(request.getDocContent())
                .build();

        // 异步执行
        sseExecutor.submit(() -> {
            // 关键点：在 SSE 线程中恢复 SecurityContext
            // 这样 DeepSeekChatAgent.stream() 就能捕获到正确的身份了
            SecurityContextHolder.setContext(mainThreadSecurityContext);
            
            StringBuilder fullAnswer = new StringBuilder();
            try {
                chatAgent.stream(context)
                    .doOnNext(chunk -> {
                        try {
                            fullAnswer.append(chunk);
                            emitter.send(SseEmitter.event().data(chunk));
                        } catch (IOException e) {
                            throw new RuntimeException("Client disconnected", e);
                        }
                    })
                    .doOnComplete(() -> {
                        handleComplete(sessionId, userIdLong, query, assistantId, fullAnswer.toString());
                        emitter.complete();
                    })
                    .doOnError(e -> {
                        handleError(sessionId, assistantId, fullAnswer.toString(), e);
                        emitter.completeWithError(e);
                    })
                    .subscribe();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                // 记得清理，防止线程池污染
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
