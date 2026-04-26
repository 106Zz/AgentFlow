package com.agenthub.api.agent_engine.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 评估大盘数据 VO
 *
 * @author AgentHub
 * @since 2026-04-26
 */
public class EvalDashboardVO {

    @Data
    @Builder
    public static class EvalSummary {
        /** Case 总数 */
        private Long totalCases;
        /** 完成（通过）数 */
        private Long completedCases;
        /** 失败数 */
        private Long failedCases;
        /** 总通过率 (%) */
        private Double passRate;
        /** AI Judge 拦截率 (%) */
        private Double aiInterceptRate;
        /** 平均耗时 (ms) */
        private Double avgDurationMs;
        /** 已评估数 */
        private Long evaluatedCount;
    }

    @Data
    @Builder
    public static class TrendPoint {
        /** 日期 (yyyy-MM-dd) */
        private String date;
        /** 当日 Case 总数 */
        private Long count;
        /** 当日通过率 (%) */
        private Double passRate;
    }

    @Data
    @Builder
    public static class ErrorBreakdown {
        /** 错误类型 -> 数量 */
        private List<ErrorItem> items;
    }

    @Data
    @Builder
    public static class ErrorItem {
        /** 错误类型名称 */
        private String type;
        /** 数量 */
        private Long count;
        /** 占比 (%) */
        private Double percentage;
    }

    @Data
    @Builder
    public static class BadCaseItem {
        /** Case ID */
        private String caseId;
        /** 用户问题 */
        private String query;
        /** 场景 */
        private String scenario;
        /** 错误类型 */
        private String errorType;
        /** 错误原因 */
        private String errorReason;
        /** 请求时间 */
        private String requestTime;
        /** 耗时 (ms) */
        private Integer durationMs;
    }

    @Data
    @Builder
    public static class CaseDetail {
        private String caseId;
        private String scenario;
        private String intent;
        private String status;
        private String query;
        private Object contextData;
        private Object promptData;
        private String rawResponse;
        private Object ruleJudgeResult;
        private Object aiJudgeResult;
        private String errorMessage;
        private String requestTime;
        private String responseTime;
        private Integer durationMs;
    }
}
