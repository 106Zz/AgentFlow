package com.agenthub.api.knowledge.service.impl;

import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.domain.vo.ChatRequest;
import com.agenthub.api.knowledge.domain.vo.ChatResponse;
import com.agenthub.api.knowledge.mapper.ChatHistoryMapper;
import com.agenthub.api.knowledge.service.IChatService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天服务实现类
 */
@Service
public class ChatServiceImpl implements IChatService {

    @Autowired
    private ChatHistoryMapper chatHistoryMapper;

    @Override
    public ChatResponse chat(ChatRequest request) {
        // TODO: 实现RAG问答逻辑
        // 1. 向量检索
        // 2. 调用LLM生成回答
        // 3. 保存聊天历史
        
        ChatResponse response = new ChatResponse();
        response.setSessionId(request.getSessionId());
        response.setAnswer("这是一个示例回答，实际实现需要集成Spring AI和向量检索");
        response.setResponseTime(100L);
        
        return response;
    }

    @Override
    public List<ChatHistory> getChatHistory(String sessionId, Long userId) {
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId)
                .eq(ChatHistory::getUserId, userId)
                .orderByAsc(ChatHistory::getCreateTime);
        
        return chatHistoryMapper.selectList(wrapper);
    }

    @Override
    public boolean clearChatHistory(String sessionId, Long userId) {
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId)
                .eq(ChatHistory::getUserId, userId);
        
        return chatHistoryMapper.delete(wrapper) > 0;
    }
}
