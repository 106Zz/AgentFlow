package com.agenthub.api.knowledge.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表展示 VO
 */
@Data
public class ChatSessionVO {
    
    /**
     * 会话ID (用于查询详情)
     */
    private String sessionId;
    
    /**
     * 会话标题
     */
    private String title;
    
    /**
     * 最后活跃时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastMessageTime;
    
    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
