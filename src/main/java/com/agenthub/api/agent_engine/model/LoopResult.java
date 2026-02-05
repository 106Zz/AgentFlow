package com.agenthub.api.agent_engine.model;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 循环执行结果
 */
@Builder
public record LoopResult(
        // ==================== 执行状态 ====================

        /**
         * 执行是否成功
         * <p>true 表示循环正常完成（评估通过或达到正常终止条件）</p>
         * <p>false 表示异常终止（超时或错误）</p>
         */
        @JsonProperty("success")
        boolean success,

        /**
         * 终止原因
         * <p>描述循环结束的原因，如"评估通过"、"达到最大轮数仍未通过评估"、"执行超时"、"执行错误: ..."等</p>
         */
        @JsonProperty("terminationReason")
        String terminationReason,

        // ==================== 最终输出 ====================

        /**
         * 最终回答
         * <p>LLM 生成的最终回答内容，这是返回给用户的主要输出</p>
         */
        @JsonProperty("finalAnswer")
        String finalAnswer,

        /**
         * 最终思考内容
         * <p>如果使用了深度思考模型（如 DeepSeek R1），此处记录其推理过程</p>
         */
        @JsonProperty("finalThinking")
        String finalThinking,

        /**
         * 最终评估结果
         * <p>最后一次质量评估的结果，用于判断回答质量是否达标</p>
         */
        @JsonProperty("finalEvaluation")
        EvaluationResult finalEvaluation,

        // ==================== 完整历史 ====================

        /**
         * 轮次历史记录
         * <p>完整记录每一轮的 RoundRecord，包括思考、行动、评估过程</p>
         */
        @JsonProperty("roundHistory")
        List<RoundRecord> roundHistory,

        /**
         * 工具调用摘要
         * <p>所有工具调用的摘要信息列表</p>
         */
        @JsonProperty("toolCallsSummary")
        List<String> toolCallsSummary,

        // ==================== 统计信息 ====================

        /**
         * 实际执行轮数
         * <p>实际执行的轮次数，可能小于配置的最大轮数</p>
         */
        @JsonProperty("actualRounds")
        int actualRounds,

        /**
         * 总耗时 (毫秒)
         * <p>整个循环执行过程的总时间</p>
         */
        @JsonProperty("totalDurationMs")
        long totalDurationMs,

        /**
         * 结束时间戳 (毫秒)
         * <p>循环执行结束的时间戳</p>
         */
        @JsonProperty("endTime")
        Long endTime
) {
    /**
     * 构造器，处理默认值
     */
    public LoopResult {
        if (endTime == null) {
            endTime = Instant.now().toEpochMilli();
        }
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建成功的执行结果（评估通过）
     *
     * @param finalAnswer   最终回答
     * @param finalThinking 最终思考内容
     * @param history       轮次历史
     * @param durationMs    总耗时
     * @return LoopResult 实例
     */
    public static LoopResult success(String finalAnswer, String finalThinking,
                                      List<RoundRecord> history, long durationMs) {
        return LoopResult.builder()
                .success(true)
                .terminationReason("评估通过")
                .finalAnswer(finalAnswer)
                .finalThinking(finalThinking)
                .roundHistory(history)
                .actualRounds(history.size())
                .totalDurationMs(durationMs)
                .finalEvaluation(history.isEmpty() ? null :
                        history.get(history.size() - 1).evaluation())
                .build();
    }

    /**
     * 创建带原因的执行结果（成功但非评估通过）
     *
     * @param terminationReason 终止原因
     * @param finalAnswer       最终回答
     * @param history           轮次历史
     * @param durationMs        总耗时
     * @return LoopResult 实例
     */
    public static LoopResult successWithReason(String terminationReason, String finalAnswer,
                                      List<RoundRecord> history, long durationMs) {
        return LoopResult.builder()
                .success(true)
                .terminationReason(terminationReason)
                .finalAnswer(finalAnswer)
                .roundHistory(history)
                .actualRounds(history.size())
                .totalDurationMs(durationMs)
                .finalEvaluation(history.isEmpty() ? null :
                        history.get(history.size() - 1).evaluation())
                .build();
    }

    /**
     * 创建达到最大轮数的执行结果
     *
     * @param finalAnswer 最终回答
     * @param history     轮次历史
     * @param durationMs  总耗时
     * @return LoopResult 实例
     */
    public static LoopResult maxRoundsReached(String finalAnswer, List<RoundRecord> history,
                                               long durationMs) {
        return LoopResult.builder()
                .success(false)
                .terminationReason("达到最大轮数仍未通过评估")
                .finalAnswer(finalAnswer)
                .roundHistory(history)
                .actualRounds(history.size())
                .totalDurationMs(durationMs)
                .finalEvaluation(history.isEmpty() ? null :
                        history.get(history.size() - 1).evaluation())
                .build();
    }

    /**
     * 创建超时的执行结果
     *
     * @param partialAnswer 部分回答（已生成的内容）
     * @param history       轮次历史
     * @param durationMs    总耗时
     * @return LoopResult 实例
     */
    public static LoopResult timeout(String partialAnswer, List<RoundRecord> history,
                                      long durationMs) {
        return LoopResult.builder()
                .success(false)
                .terminationReason("执行超时")
                .finalAnswer(partialAnswer)
                .roundHistory(history)
                .actualRounds(history.size())
                .totalDurationMs(durationMs)
                .build();
    }

    /**
     * 创建错误的执行结果
     *
     * @param errorMessage 错误信息
     * @param history      轮次历史
     * @param durationMs   总耗时
     * @return LoopResult 实例
     */
    public static LoopResult error(String errorMessage, List<RoundRecord> history,
                                    long durationMs) {
        return LoopResult.builder()
                .success(false)
                .terminationReason("执行错误: " + errorMessage)
                .finalAnswer("抱歉，处理过程中发生错误。")
                .roundHistory(history)
                .actualRounds(history.size())
                .totalDurationMs(durationMs)
                .build();
    }

    // ==================== 查询方法 ====================

    /**
     * 判断是否有工具调用记录
     *
     * @return true 如果至少有一轮执行了工具调用
     */
    public boolean hasToolCalls() {
        return roundHistory != null && roundHistory.stream()
                .anyMatch(r -> r.toolCalls() != null && !r.toolCalls().isEmpty());
    }

    /**
     * 获取总工具调用次数
     *
     * @return 所有轮次中工具调用的总次数
     */
    public int getTotalToolCalls() {
        if (roundHistory == null) return 0;
        return roundHistory.stream()
                .mapToInt(r -> r.toolCalls() != null ? r.toolCalls().size() : 0)
                .sum();
    }

    /**
     * 判断最终评估是否通过
     *
     * @return true 如果最终评估通过
     */
    public boolean isFinalEvaluationPassed() {
        return finalEvaluation != null && finalEvaluation.isPassed();
    }

    // ==================== 格式化方法 ====================

    /**
     * 获取结果摘要 (用于日志输出)
     *
     * @return 格式化的摘要字符串
     */
    public String getSummary() {
        return String.format(
                "LoopResult[%s] rounds=%d, tools=%d, duration=%dms, reason=%s",
                success ? "SUCCESS" : "FAILURE",
                actualRounds,
                getTotalToolCalls(),
                totalDurationMs,
                terminationReason
        );
    }

    /**
     * 获取状态描述
     * <p>返回用户友好的状态描述字符串</p>
     *
     * @return 状态描述
     */
    public String getStatusDescription() {
        if (success) {
            if (isFinalEvaluationPassed()) {
                return "回答已通过质量评估";
            } else {
                return terminationReason;
            }
        } else {
                return terminationReason;
        }
    }

    /**
     * 获取调试信息
     * <p>返回完整的调试信息，包括状态、轮次记录、评估结果等</p>
     *
     * @return 格式化的调试信息字符串
     */
    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 循环执行结果 ===\n");
        sb.append("状态: ").append(success ? "成功" : "失败").append("\n");
        sb.append("终止原因: ").append(terminationReason).append("\n");
        sb.append("执行轮数: ").append(actualRounds).append("\n");
        sb.append("总耗时: ").append(totalDurationMs).append("ms\n");
        sb.append("工具调用: ").append(getTotalToolCalls()).append(" 次\n");

        if (finalEvaluation != null) {
            sb.append("最终评估: ")
                    .append(finalEvaluation.isPassed() ? "通过" : "失败")
                    .append("\n");
            if (finalEvaluation.getReason() != null) {
                sb.append("评估原因: ").append(finalEvaluation.getReason()).append("\n");
            }
        }

        sb.append("\n=== 轮次记录 ===\n");
        if (roundHistory != null) {
            for (RoundRecord round : roundHistory) {
                sb.append(round.getSummary()).append("\n");
            }
        }

        return sb.toString();
    }
}
