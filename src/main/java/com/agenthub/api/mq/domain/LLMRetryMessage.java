package com.agenthub.api.mq.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * LLM 请求重试消息
 * <p>用于处理意图识别和 Worker 请求的限流重试</p>
 *
 * @author AgentHub
 * @since 2026-02-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMRetryMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 请求类型：INTENT_RECOGNITION（意图识别）或 WORKER（Worker LLM）
     */
    private RequestType requestType;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 用户查询
     */
    private String query;

    /**
     * 额外参数（JSON 格式）
     */
    private String extraParams;

    /**
     * 重试次数
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private int maxRetryCount = 3;

    /**
     * 错误信息（上一次失败原因）
     */
    private String errorMessage;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 请求类型枚举
     */
    public enum RequestType {
        /**
         * 意图识别请求
         */
        INTENT_RECOGNITION,

        /**
         * Worker LLM 请求
         */
        WORKER
    }

    /**
     * 是否需要重试
     */
    public boolean needRetry() {
        return retryCount < maxRetryCount;
    }

    /**
     * 创建下一次重试消息
     */
    public LLMRetryMessage nextRetry(String errorMessage) {
        return LLMRetryMessage.builder()
                .requestType(this.requestType)
                .sessionId(this.sessionId)
                .userId(this.userId)
                .query(this.query)
                .extraParams(this.extraParams)
                .retryCount(this.retryCount + 1)
                .maxRetryCount(this.maxRetryCount)
                .errorMessage(errorMessage)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建意图识别重试消息
     */
    public static LLMRetryMessage forIntentRecognition(String sessionId, String userId, String query) {
        return LLMRetryMessage.builder()
                .requestType(RequestType.INTENT_RECOGNITION)
                .sessionId(sessionId)
                .userId(userId)
                .query(query)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建 Worker 请求重试消息
     */
    public static LLMRetryMessage forWorker(String sessionId, String userId, String query, String extraParams) {
        return LLMRetryMessage.builder()
                .requestType(RequestType.WORKER)
                .sessionId(sessionId)
                .userId(userId)
                .query(query)
                .extraParams(extraParams)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
