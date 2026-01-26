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

    /**
     * 创建会话骨架（Write-Ahead）
     * 先保存用户问题和空的回答记录，确保问题不会丢失
     *
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @param question 用户问题
     * @return 创建的回答记录ID（用于后续更新）
     */
    Long createChatSkeleton(String sessionId, Long userId, String question);

    /**
     * 更新回答内容
     *
     * @param id 回答记录ID
     * @param answer 完整回答内容
     * @param status 状态
     */
    void updateAnswer(Long id, String answer, String status);

    /**
     * 标记生成中断
     *
     * @param id 回答记录ID
     * @param partialAnswer 已生成的部分内容
     * @param errorMsg 错误信息
     */
    void markAsInterrupted(Long id, String partialAnswer, String errorMsg);
}
