package com.agenthub.api.knowledge.controller;

import cn.hutool.core.util.IdUtil;
import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.domain.vo.ChatRequest;
import com.agenthub.api.knowledge.domain.vo.ChatResponse;
import com.agenthub.api.knowledge.service.IChatService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 智能问答
 */
@Tag(name = "智能问答")
@RestController
@RequestMapping("/knowledge/chat")
public class ChatController extends BaseController {

    @Autowired
    private IChatService chatService;

    @Operation(summary = "发送问题")
    @PostMapping("/ask")
    public AjaxResult ask(@Valid @RequestBody ChatRequest request) {
        // 如果没有会话ID，生成一个新的
        if (request.getSessionId() == null) {
            request.setSessionId(IdUtil.simpleUUID());
        }
        
        ChatResponse response = chatService.chat(request);
        return success(response);
    }

    @Operation(summary = "获取聊天历史")
    @GetMapping("/history/{sessionId}")
    public AjaxResult getHistory(@PathVariable String sessionId) {
        Long userId = SecurityUtils.getUserId();
        List<ChatHistory> history = chatService.getChatHistory(sessionId, userId);
        return success(history);
    }

    @Operation(summary = "清空聊天历史")
    @DeleteMapping("/history/{sessionId}")
    public AjaxResult clearHistory(@PathVariable String sessionId) {
        Long userId = SecurityUtils.getUserId();
        chatService.clearChatHistory(sessionId, userId);
        return success("清空成功");
    }
}
