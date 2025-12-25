package com.agenthub.api.knowledge.domain;


import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 会话元数据对象 chat_session
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_session")
public class ChatSession extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 会话元数据ID（雪花算法生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话ID（UUID格式）
     */
    private String sessionId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 会话标题（通常是首个问题的摘要）
     */
    private String title;

    /**
     * 消息数量
     */
    private Integer messageCount;

    /**
     * 最后一条消息时间
     */
    private LocalDateTime lastMessageTime;
}
