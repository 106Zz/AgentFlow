package com.agenthub.api.knowledge.service;

import com.agenthub.api.knowledge.domain.ChatSession;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 会话管理服务接口
 */
public interface IChatSessionService extends IService<ChatSession> {
    
    /**
     * 获取用户会话列表
     */
    List<ChatSession> getUserSessions(Long userId);
    
    /**
     * 更新或创建会话
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @param question 用户的首个问题（用于生成标题）
     */
    void updateSession(String sessionId, Long userId, String question);
    
    /**
     * 删除会话
     */
    void deleteSession(String sessionId, Long userId);
}
