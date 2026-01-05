package com.agenthub.api.knowledge.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话元数据对象 chat_session
 */
@Data
@TableName("chat_session")
public class ChatSession implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话元数据ID（雪花算法生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 会话ID（UUID格式）
     */
    private String sessionId;

    /**
     * 用户ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
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

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 删除标志（0代表存在 1代表删除）
     */
    @TableLogic
    private Integer delFlag;
}
