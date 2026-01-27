package com.agenthub.api.prompt.domain.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Case 快照视图对象
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
public class CaseSnapshotVO {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * Case ID
     */
    private String caseId;

    /**
     * 场景类型
     */
    private String scenario;

    /**
     * 意图
     */
    private String intent;

    /**
     * 用户问题
     */
    private String query;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 输入数据
     */
    private JsonNode inputData;

    /**
     * 上下文数据
     */
    private JsonNode contextData;

    /**
     * 提示词数据
     */
    private JsonNode promptData;

    /**
     * 模型数据
     */
    private JsonNode modelData;

    /**
     * 输出数据
     */
    private JsonNode outputData;

    /**
     * 状态
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 请求时间
     */
    private LocalDateTime requestTime;

    /**
     * 响应时间
     */
    private LocalDateTime responseTime;

    /**
     * 耗时（毫秒）
     */
    private Integer durationMs;

    /**
     * Token 使用量
     */
    private TokenUsageVO tokenUsage;

    /**
     * 是否已评估
     */
    private Boolean isEvaluated;

    /**
     * Rule Judge 结果
     */
    private JsonNode ruleJudgeResult;

    /**
     * AI Judge 结果
     */
    private JsonNode aiJudgeResult;

    /**
     * Token 使用量 VO
     */
    @Data
    @Builder
    public static class TokenUsageVO {
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer totalTokens;
    }
}
