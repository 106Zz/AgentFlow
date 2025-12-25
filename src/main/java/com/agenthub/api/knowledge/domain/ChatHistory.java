package com.agenthub.api.knowledge.domain;


import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天历史对象 chat_history
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_history")
public class ChatHistory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 聊天记录ID（雪花算法生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户问题
     */
    private String question;

    /**
     * AI回答
     */
    private String answer;

    /**
     * 引用的知识来源（JSONB格式）
     */
    private String sources;

    /**
     * Token消耗数量（用于成本统计）
     */
    private Integer tokenCount;

    /**
     * 问答类型（general普通问答/policy政策咨询/calculation计算分析）
     */
    private String questionType;

    /**
     * 响应时间（毫秒）
     */
    private Long responseTime;
}
