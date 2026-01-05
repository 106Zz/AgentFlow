package com.agenthub.api.knowledge.controller;

import com.agenthub.api.ai.service.impl.RagChatServiceImpl;
import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.ChatSession;
import com.agenthub.api.knowledge.domain.vo.ChatRequest;
import com.agenthub.api.knowledge.domain.vo.ChatResponse;
import com.agenthub.api.knowledge.domain.vo.ChatSessionVO;
import com.agenthub.api.knowledge.domain.vo.StreamChunk;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 智能问答控制器
 */
@Tag(name = "智能问答")
@RestController
@RequestMapping("/knowledge/chat")
@RequiredArgsConstructor
public class ChatController extends BaseController {

    private final IChatService chatService;
    private final RagChatServiceImpl ragChatService;
    private final IChatHistoryService chatHistoryService;
    private final com.agenthub.api.knowledge.service.IChatSessionService chatSessionService;

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
     * 流式问答（逐字返回，支持思考过程展示）
     */
    @Operation(summary = "流式问答")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamChunk> stream(@Valid @RequestBody ChatRequest request) {
        Long userId = SecurityUtils.getUserId();
        
        // 1. 生成或验证 sessionId
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        // 2. 调用流式 RAG 服务 (返回 StreamChunk)
        Flux<StreamChunk> answerStream = ragChatService.chatStream(sessionId, request.getQuestion());

        // 3. 收集完整答案并保存（异步）
        final String finalSessionId = sessionId;
        StringBuilder fullAnswer = new StringBuilder();
        
        return answerStream
                .map(chunk -> {
                    // 注入 SessionId，确保前端能拿到（尤其是新建会话时）
                    chunk.setSessionId(finalSessionId);
                    return chunk;
                })
                .doOnNext(chunk -> {
                    // 只收集正文内容用于存储，忽略思考过程
                    if (chunk.getContent() != null) {
                        fullAnswer.append(chunk.getContent());
                    }
                })
                .doOnComplete(() -> {
                    // 流式输出完成后，保存完整的聊天历史
                    String answer = fullAnswer.toString();
                    if (!answer.isEmpty()) {
                        // 1. 保存详细对话记录
                        chatHistoryService.saveChat(finalSessionId, userId, 
                                                   request.getQuestion(), answer);
                        // 2. 更新会话状态（异步生成标题等）
                        chatSessionService.updateSession(finalSessionId, userId, request.getQuestion());
                    }
                });
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
        // 切换为从 ChatSession 表查询，性能更好且有标题
        List<ChatSession> sessions = chatSessionService.getUserSessions(userId);
        
        // 转换为 VO
        List<ChatSessionVO> vos = sessions.stream().map(s -> {
            ChatSessionVO vo = new ChatSessionVO();
            vo.setSessionId(s.getSessionId());
            vo.setTitle(s.getTitle());
            vo.setLastMessageTime(s.getLastMessageTime());
            vo.setCreateTime(s.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
        
        return success(vos);
    }

    /**
     * 清空聊天历史
     */
    @Operation(summary = "删除会话")
    @DeleteMapping("/history/{sessionId}")
    public AjaxResult clearHistory(@PathVariable String sessionId) {
        Long userId = SecurityUtils.getUserId();
        // 同时删除历史记录和会话元数据
        boolean success = chatService.clearChatHistory(sessionId, userId);
        chatSessionService.deleteSession(sessionId, userId);
        return success ? success("会话已删除") : error("删除失败");
    }
}
