package com.agenthub.api.knowledge.service.impl;

import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.domain.vo.ChatRequest;
import com.agenthub.api.knowledge.domain.vo.ChatResponse;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.agenthub.api.knowledge.service.IChatService;
import com.agenthub.api.knowledge.service.IChatSessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天服务实现类
 *
 * 注意：chat() 方法已废弃，RAG 功能已迁移到 ChatUseCase + PowerKnowledgeTool 架构
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    // RagChatServiceImpl 已删除，RAG 功能通过 ChatUseCase 处理
    private final IChatHistoryService chatHistoryService;
    private final IChatSessionService chatSessionService;

    @Override
    public ChatResponse chat(ChatRequest request) {
        // ⚠️ 此方法已废弃
        // RAG 功能已迁移到 ChatUseCase + PowerKnowledgeTool 架构
        // 请使用 AIUnifiedController 进行对话
        throw new UnsupportedOperationException(
                "chat() 方法已废弃。请使用 /api/ai/unified/chat 接口进行对话。"
        );

        // Long userId = SecurityUtils.getUserId();
        // ... 以下代码已废弃 ...
    }

    @Override
    public List<ChatHistory> getChatHistory(String sessionId, Long userId) {
        return chatHistoryService.getBySessionId(sessionId, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean clearChatHistory(String sessionId, Long userId) {
        // 1. 删除聊天历史
        chatHistoryService.deleteBySessionId(sessionId, userId);
        // 2. 删除会话元数据
        chatSessionService.deleteSession(sessionId, userId);
        return true;
    }

    /**
     * 验证会话是否属于当前用户
     */
    private boolean validateSessionOwnership(String sessionId, Long userId) {
        long count = chatHistoryService.count(
            new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getSessionId, sessionId)
                .eq(ChatHistory::getUserId, userId)
        );
        return count > 0;
    }
}
