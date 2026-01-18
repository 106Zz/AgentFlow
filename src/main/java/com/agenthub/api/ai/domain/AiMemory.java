package com.agenthub.api.ai.domain;

import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 引擎上下文记忆表
 * 用于存储 Spring AI 的 List<Message> 序列化数据
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_ai_memory")
public class AiMemory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话唯一标识 (Session ID)
     * 对应 Spring AI 的 conversationId
     */
    private String sessionId;

    /**
     * 用户 ID
     * 用于数据隔离和权限管理
     */
    private Long userId;

    /**
     * 消息列表的 JSON 序列化字符串
     * 存储 List<Message>
     */
    private String messagesJson;
}