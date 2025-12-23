package com.agenthub.api.knowledge.domain.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求对象
 */
@Data
public class ChatRequest {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户问题
     */
    @NotBlank(message = "问题不能为空")
    private String question;

    /**
     * 是否使用RAG检索（默认true）
     */
    private Boolean useRag = true;

    /**
     * 检索的文档数量（默认5）
     */
    private Integer topK = 5;

    /**
     * 相似度阈值（0-1，默认0.7）
     */
    private Double similarityThreshold = 0.7;
}
