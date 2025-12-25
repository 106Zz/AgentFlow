package com.agenthub.api.knowledge.controller;

import com.agenthub.api.ai.service.impl.RagChatServiceImplV2;
import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.vo.ChatRequest;
import com.agenthub.api.knowledge.domain.vo.ChatResponse;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.agenthub.api.knowledge.service.IChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;

/**
 * 智能问答控制器
 */
@Tag(name = "智能问答")
@RestController
@RequestMapping("/knowledge/chat")
@RequiredArgsConstructor
public class ChatController extends BaseController {

    private final IChatService chatService;
    private final RagChatServiceImplV2 ragChatService;
    private final IChatHistoryService chatHistoryService;

    /**
     * 普通问答（一次性返回完整答案）
     */
    @Operation(summary = "普通问答")
    @PostMapping("/ask")
    public AjaxResult ask(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request);
        return success(response);
    }

    /**
     * 流式问答（逐字返回，类似 ChatGPT）
     */
    @Operation(summary = "流式问答")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        Long userId = SecurityUtils.getUserId();
        
        // 1. 生成或验证 sessionId
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 2. 调用流式 RAG 服务
        Flux<String> answerStream = ragChatService.chatStream(sessionId, request.getQuestion());

        // 3. 收集完整答案并保存（异步）
        final String finalSessionId = sessionId;
        StringBuilder fullAnswer = new StringBuilder();
        
        return answerStream
                .doOnNext(fullAnswer::append)  // 收集每个片段
                .doOnComplete(() -> {
                    // 流式输出完成后，保存完整的聊天历史
                    chatHistoryService.saveChat(finalSessionId, userId, 
                                               request.getQuestion(), fullAnswer.toString());
                })
                .delayElements(Duration.ofMillis(10));  // 控制输出速度（可选）
    }

    /**
     * 获取聊天历史
     */
    @Operation(summary = "获取聊天历史")
    @GetMapping("/history/{sessionId}")
    public AjaxResult getHistory(@PathVariable String sessionId) {
        Long userId = SecurityUtils.getUserId();
        return success(chatService.getChatHistory(sessionId, userId));
    }

    /**
     * 获取用户的所有会话列表
     */
    @Operation(summary = "获取会话列表")
    @GetMapping("/sessions")
    public AjaxResult getSessions() {
        Long userId = SecurityUtils.getUserId();
        return success(chatHistoryService.getUserSessions(userId));
    }

    /**
     * 清空聊天历史
     */
    @Operation(summary = "清空聊天历史")
    @DeleteMapping("/history/{sessionId}")
    public AjaxResult clearHistory(@PathVariable String sessionId) {
        Long userId = SecurityUtils.getUserId();
        boolean success = chatService.clearChatHistory(sessionId, userId);
        return success ? success("聊天历史已清空") : error("清空失败");
    }
}
