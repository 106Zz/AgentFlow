package com.agenthub.api.ai.controller;

import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.domain.ChatRequestDTO;
import com.agenthub.api.ai.service.RouterService;
import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.agenthub.api.knowledge.service.IChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * AI 统一入口控制器 (Unified AI Controller)
 * 职责: 处理所有 AI 相关的请求 (对话、合规、计算)，并根据响应类型智能分发。
 */
@Slf4j
@Tag(name = "AI 核心服务")
@RestController
@RequestMapping("/ai/v1")
@RequiredArgsConstructor
public class AIUnifiedController extends BaseController {

    private final RouterService routerService;
    private final IChatSessionService chatSessionService;
    private final IChatHistoryService chatHistoryService;

    /**
     * 统一对话接口
     * 支持流式 (Stream) 和非流式 (JSON) 两种返回格式，由后端自动判断。
     * 前端建议使用 fetch 或 EventSource 接收，并根据 Content-Type 头判断处理方式。
     */
    @Operation(summary = "智能对话/任务执行")
    @PostMapping(value = "/chat", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chat(@RequestBody ChatRequestDTO requestDto) {
        Long userId = SecurityUtils.getUserId();
        String sessionId = StringUtils.hasText(requestDto.sessionId()) ? requestDto.sessionId() : UUID.randomUUID().toString();

        log.info("接收 AI 请求: user={}, session={}, query={}", userId, sessionId, requestDto.query());

        // 1. 构造核心请求对象
        AIRequest aiRequest = new AIRequest(
                requestDto.query(),
                requestDto.docContent(),
                String.valueOf(userId),
                sessionId
        );

        // 2. 调用路由服务
        AIResponse response = routerService.handleRequest(aiRequest);

        // 3. 根据响应类型分发返回结果
        // 保存会话信息（用于流式完成后持久化）
        final String finalSessionId = sessionId;
        final String finalQuery = requestDto.query();
        final Long finalUserId = userId;

        return switch (response.getType()) {
            // 场景 A: 流式对话 (Chat) -> 返回 Flux<String> (SSE)
            case STREAM -> {
                @SuppressWarnings("unchecked")
                Flux<String> stream = (Flux<String>) response.getStreamData();

                // 收集完整答案用于持久化
                StringBuilder fullAnswer = new StringBuilder();

                yield stream
                        .doOnNext(chunk -> {
                            // 收集响应内容
                            fullAnswer.append(chunk);
                        })
                        .doOnComplete(() -> {
                            // 流式输出完成后，保存完整的聊天历史
                            String answer = fullAnswer.toString();
                            if (!answer.isEmpty()) {
                                try {
                                    // 1. 保存详细对话记录到 chat_history 表
                                    chatHistoryService.saveChat(finalSessionId, finalUserId, finalQuery, answer);
                                    // 2. 更新会话状态到 chat_session 表（异步生成标题等）
                                    chatSessionService.updateSession(finalSessionId, finalUserId, finalQuery);
                                    log.info("会话保存成功: sessionId={}, userId={}", finalSessionId, finalUserId);
                                } catch (Exception e) {
                                    log.error("保存会话失败: sessionId={}, userId={}", finalSessionId, finalUserId, e);
                                }
                            }
                        })
                        .map(text -> "data: " + text + "\n\n");
            }

            // 场景 B: 结构化报告 (Audit) -> 异步等待结果 -> 返回标准 JSON
            case REPORT -> {
                CompletableFuture<?> future = response.getAsyncData();
                // 阻塞等待异步结果 (Spring MVC 会自动处理异步，但为了统一 AjaxResult 结构，这里 join 一下或者用 DeferredResult)
                // 简单做法: join 后返回
                try {
                    yield success(future.join());
                } catch (Exception e) {
                    log.error("AI 任务执行失败", e);
                    yield error("任务执行失败: " + e.getMessage());
                }
            }

            // 场景 C: 数据列表 (Calc) / 普通文本 (Text)
            case LIST, TEXT -> {
                try {
                    yield success(response.getAsyncData().join());
                } catch (Exception e) {
                    yield error("计算服务异常");
                }
            }
        };
    }
}
