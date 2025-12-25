package com.agenthub.api.knowledge.service;

import com.agenthub.api.knowledge.domain.ChatHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 聊天历史服务接口
 */
public interface IChatHistoryService extends IService<ChatHistory> {

    /**
     * 保存聊天记录
     */
    void saveChat(String sessionId, Long userId, String question, String answer);

    /**
     * 获取会话的完整历史
     */
    List<ChatHistory> getBySessionId(String sessionId, Long userId);

    /**
     * 获取用户的所有会话列表
     */
    List<Map<String, Object>> getUserSessions(Long userId);

    /**
     * 删除会话
     */
    void deleteBySessionId(String sessionId, Long userId);
}
