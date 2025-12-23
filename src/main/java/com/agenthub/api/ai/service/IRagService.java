package com.agenthub.api.ai.service;

import com.agenthub.api.knowledge.domain.vo.ChatRequest;
import com.agenthub.api.knowledge.domain.vo.ChatResponse;

/**
 * RAG问答服务接口
 */
public interface IRagService {

    /**
     * RAG问答
     * 
     * @param request 问答请求
     * @param userId 用户ID
     * @param isAdmin 是否为管理员
     * @return 问答响应
     */
    ChatResponse chat(ChatRequest request, Long userId, boolean isAdmin);

    /**
     * 普通问答（不使用RAG）
     * 
     * @param question 问题
     * @return 回答
     */
    String chatWithoutRag(String question);
}
