package com.agenthub.api.knowledge.service.impl;

import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.mapper.ChatHistoryMapper;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天历史服务实现
 * 
 * 策略：滑动窗口记忆
 * - Spring AI 的 MessageWindowChatMemory 自动管理（最近 N 条）
 * - Redis 存储对话记忆（24h TTL）
 * - PostgreSQL 存储完整历史（永久）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> 
        implements IChatHistoryService {

    private final ChatHistoryMapper chatHistoryMapper;
    private final ChatMemoryRepository chatMemoryRepository;

    /**
     * 保存聊天记录到 PostgreSQL
     * 注意：对话记忆由 Spring AI 自动管理（存储在 Redis）
     */
    @Override
    public void saveChat(String sessionId, Long userId, String question, String answer) {
        try {
            ChatHistory history = new ChatHistory();
            history.setSessionId(sessionId);
            history.setUserId(userId);
            history.setQuestion(question);
            history.setAnswer(answer);
            
            boolean success = save(history);
            if (success) {
                log.info("聊天记录已保存：sessionId={}, userId={}, ID={}", sessionId, userId, history.getId());
            } else {
                log.error("聊天记录保存失败：sessionId={}, userId={}", sessionId, userId);
            }
        } catch (Exception e) {
            log.error("保存聊天记录发生异常: sessionId={}", sessionId, e);
            throw e; // 抛出异常以中断流程（如事务回滚）
        }
    }

    /**
     * 获取会话的完整历史（从 PostgreSQL）
     */
    @Override
    public List<ChatHistory> getBySessionId(String sessionId, Long userId) {
        return list(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getSessionId, sessionId)
                .eq(ChatHistory::getUserId, userId)
                .orderByAsc(ChatHistory::getCreateTime));
    }

    /**
     * 获取用户的所有会话列表
     */
    @Override
    public List<Map<String, Object>> getUserSessions(Long userId) {
        return baseMapper.selectUserSessions(userId);
    }

    /**
     * 删除会话（PostgreSQL + Redis）
     */
    @Override
    public void deleteBySessionId(String sessionId, Long userId) {
        // 删除 PostgreSQL 记录
        remove(new LambdaQueryWrapper<ChatHistory>()
                .eq(ChatHistory::getSessionId, sessionId)
                .eq(ChatHistory::getUserId, userId));
        
        // Redis 的对话记忆会自动过期（24h TTL）
        chatMemoryRepository.deleteByConversationId(sessionId);
        log.info("会话已删除：sessionId={}, userId={}", sessionId, userId);
    }
}
