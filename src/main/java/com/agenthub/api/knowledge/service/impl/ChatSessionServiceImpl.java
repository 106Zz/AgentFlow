package com.agenthub.api.knowledge.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.knowledge.domain.ChatSession;
import com.agenthub.api.knowledge.mapper.ChatSessionMapper;
import com.agenthub.api.knowledge.service.IChatSessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话管理服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> 
        implements IChatSessionService {

    private final ChatModel chatModel;

    @Override
    public List<ChatSession> getUserSessions(Long userId) {
        return baseMapper.selectUserSessions(userId);
    }

    @Async("taskExecutor") // 异步执行，不阻塞主线程
    @Override
    public void updateSession(String sessionId, Long userId, String question) {
        try {
            // 1. 查找是否存在该会话
            ChatSession session = getOne(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getSessionId, sessionId));

            if (session == null) {
                // 2. 如果不存在，则是新会话 -> 创建并生成标题
                session = new ChatSession();
                session.setSessionId(sessionId);
                session.setUserId(userId);
                session.setMessageCount(1);
                session.setLastMessageTime(LocalDateTime.now());
                
                // 智能生成标题
                String title = generateSmartTitle(question);
                session.setTitle(title);
                
                save(session);
                log.info("创建新会话: {}，标题: {}", sessionId, title);
            } else {
                // 3. 如果存在，则是旧会话 -> 更新时间和计数
                session.setLastMessageTime(LocalDateTime.now());
                session.setMessageCount(session.getMessageCount() + 1);
                updateById(session);
            }
        } catch (Exception e) {
            log.error("更新会话状态失败: {}", sessionId, e);
        }
    }

    @Override
    public void deleteSession(String sessionId, Long userId) {
        remove(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId));
    }

    /**
     * 调用 AI 生成简短标题
     */
    private String generateSmartTitle(String question) {
        if (StrUtil.isBlank(question)) return "新会话";
        
        // 如果问题很短，直接用问题当标题
        if (question.length() < 10) return question;

        try {
            // 使用简单的 ChatClient 调用模型生成摘要
            String prompt = "请把以下问题总结为一个10字以内的简短标题，不要包含任何标点符号：\n" + question;
            return ChatClient.create(chatModel).prompt(prompt).call().content();
        } catch (Exception e) {
            log.warn("AI生成标题失败，使用默认截取", e);
            // 降级策略：直接截取前20个字
            return StrUtil.sub(question, 0, 20);
        }
    }
}
