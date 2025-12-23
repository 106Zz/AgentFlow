package com.agenthub.api.knowledge.service;

import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.domain.vo.ChatRequest;
import com.agenthub.api.knowledge.domain.vo.ChatResponse;

import java.util.List;

/**
 * 聊天服务 业务层
 */
public interface IChatService {

    /**
     * RAG问答
     */
    ChatResponse chat(ChatRequest request);

    /**
     * 获取用户聊天历史
     */
    List<ChatHistory> getChatHistory(String sessionId, Long userId);

    /**
     * 清空聊天历史
     */
    boolean clearChatHistory(String sessionId, Long userId);
}
