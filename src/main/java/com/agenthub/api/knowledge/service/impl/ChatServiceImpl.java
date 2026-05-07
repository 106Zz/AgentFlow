package com.agenthub.api.knowledge.service.impl;

import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.domain.ChatSession;
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
        List<ChatHistory> history = chatHistoryService.getBySessionId(sessionId, userId);
        if (!history.isEmpty()) {
            return history;
        }

        boolean ownsSession = chatSessionService.count(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId)) > 0;
        if (!ownsSession) {
            return history;
        }

        log.warn("会话元数据属于当前用户但历史 user_id 不匹配，使用 sessionId 兜底读取: sessionId={}, userId={}",
                sessionId, userId);
        return chatHistoryService.list(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getSessionId, sessionId)
                .orderByAsc(ChatHistory::getCreateTime));
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
