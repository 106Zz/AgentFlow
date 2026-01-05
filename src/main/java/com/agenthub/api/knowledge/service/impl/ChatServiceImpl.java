package com.agenthub.api.knowledge.service.impl;

import com.agenthub.api.ai.service.impl.RagChatServiceImpl;
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
import java.util.UUID;

/**
 * 聊天服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final RagChatServiceImpl ragChatService;
    private final IChatHistoryService chatHistoryService;
    private final IChatSessionService chatSessionService;

    @Override
    public ChatResponse chat(ChatRequest request) {
        Long userId = SecurityUtils.getUserId();
        long startTime = System.currentTimeMillis();
        
        // 1. 生成或验证 sessionId
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            log.info("生成新会话ID: {}", sessionId);
        } else {
            // 验证 sessionId 是否属于当前用户
            if (!validateSessionOwnership(sessionId, userId)) {
                throw new RuntimeException("无效的会话ID或无权访问");
            }
        }
        
        // 2. 调用 RAG 服务获取回答（自动使用 Redis 对话记忆）
        String answer = ragChatService.chat(sessionId, request.getQuestion());
        
        // 3. 保存到 PostgreSQL（用于前端展示历史）
        chatHistoryService.saveChat(sessionId, userId, request.getQuestion(), answer);

        // 4. 更新会话列表状态（确保非流式对话也能在列表中显示）
        chatSessionService.updateSession(sessionId, userId, request.getQuestion());
        
        // 5. 构建响应
        long responseTime = System.currentTimeMillis() - startTime;
        
        ChatResponse response = new ChatResponse();
        response.setSessionId(sessionId);
        response.setAnswer(answer);
        response.setResponseTime(responseTime);
        
        log.info("问答完成 - sessionId: {}, userId: {}, 耗时: {}ms", sessionId, userId, responseTime);
        
        return response;
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
