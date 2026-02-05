package com.agenthub.api.agent_engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 单轮循环记录
 * <p>完整记录循环中一轮的过程：思考 → 行动 → 回答 → 反思</p>
 *
 * <h3>记录内容：</h3>
 * <ul>
 *   <li>思考阶段：快速思考内容、工具调用决策、推理链条</li>
 *   <li>行动阶段：工具调用列表、执行结果</li>
 *   <li>生成阶段：生成的回答、深度思考内容</li>
 *   <li>反思阶段：ReflectionService 的评估结果</li>
 * </ul>
 *
 * @author AgentHub
 * @since 2026-02-03
 */
@Builder
public record RoundRecord(
        // ==================== 基础信息 ====================

        /**
         * 轮次编号 (从 1 开始)
         */
        @JsonProperty("roundNumber")
        int roundNumber,

        // ==================== 思考阶段 ====================

        /**
         * 快速思考内容
         * <p>LLM 的初步分析和判断结果</p>
         */
        @JsonProperty("quickThought")
        String quickThought,

        /**
         * 工具调用决策
         * <p>判断是否需要调用工具，以及调用哪个工具</p>
         */
        @JsonProperty("toolDecision")
        String toolDecision,

        /**
         * 推理链条
         * <p>多步推理过程记录，便于追踪模型思维路径</p>
         */
        @JsonProperty("reasoningChain")
        List<String> reasoningChain,

        // ==================== 行动阶段 ====================

        /**
         * 执行的工具调用列表
         */
        @JsonProperty("toolCalls")
        List<ToolCall> toolCalls,

        /**
         * 工具执行结果列表
         */
        @JsonProperty("toolResults")
        List<ToolResult> toolResults,

        // ==================== 生成阶段 ====================

        /**
         * 本轮生成的回答
         * <p>LLM 生成的最终回答内容</p>
         */
        @JsonProperty("generatedAnswer")
        String generatedAnswer,

        /**
         * 深度思考内容
         * <p>如果启用了深度思考（如 DeepSeek R1），记录其推理过程</p>
         */
        @JsonProperty("deepThinkingContent")
        String deepThinkingContent,

        // ==================== 反思阶段 ====================

        /**
         * 评估结果
         * <p>ReflectionService 的质量评估结果</p>
         */
        @JsonProperty("evaluation")
        EvaluationResult evaluation,

        /**
         * 是否最后一轮
         * <p>标记是否是循环的最后一轮（最后一轮不执行评估以节省 Token）</p>
         */
        @JsonProperty("isFinalRound")
        boolean isFinalRound,

        // ==================== 时间信息 ====================

        /**
         * 本轮时间戳
         */
        @JsonProperty("timestamp")
        Long timestamp,

        /**
         * 本轮耗时 (毫秒)
         */
        @JsonProperty("durationMs")
        long durationMs
) {
    /**
     * 构造器，处理默认值
     */
    public RoundRecord {
        // 空列表默认值
        if (reasoningChain == null) {
            reasoningChain = Collections.emptyList();
        }
        if (toolCalls == null) {
            toolCalls = Collections.emptyList();
        }
        if (toolResults == null) {
            toolResults = Collections.emptyList();
        }
        // 默认时间戳
        if (timestamp == null) {
            timestamp = Instant.now().toEpochMilli();
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 判断本轮是否有工具调用
     *
     * @return true 如果有工具调用
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 判断工具调用是否全部成功
     *
     * @return true 如果所有工具都执行成功
     */
    public boolean allToolsSucceeded() {
        if (toolResults == null || toolResults.isEmpty()) {
            return true;
        }
        return toolResults.stream().allMatch(ToolResult::success);
    }

    /**
     * 判断是否通过评估
     *
     * @return true 如果评估通过
     */
    public boolean passedEvaluation() {
        return evaluation != null && evaluation.isPassed();
    }

    // ==================== 格式化方法 ====================

    /**
     * 获取本轮摘要 (用于日志输出)
     *
     * @return 格式化的摘要字符串
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[第 ").append(roundNumber).append(" 轮] ");

        if (hasToolCalls()) {
            sb.append("调用工具: ");
            if (toolCalls.size() == 1) {
                sb.append(toolCalls.get(0).toolName());
            } else {
                sb.append(toolCalls.size()).append(" 个工具");
            }
            sb.append(" | ");
        }

        if (evaluation != null) {
            sb.append("评估: ").append(evaluation.isPassed() ? "通过" : "失败");
        } else {
            sb.append("评估: 未执行");
        }

        sb.append(" | 耗时: ").append(durationMs).append("ms");

        return sb.toString();
    }

    /**
     * 获取完整的思考过程描述
     * <p>包含快速思考、推理链条和深度思考内容</p>
     *
     * @return 格式化的思考过程字符串
     */
    public String getThinkingProcess() {
        StringBuilder sb = new StringBuilder();

        if (quickThought != null && !quickThought.isBlank()) {
            sb.append("【快速思考】\n").append(quickThought).append("\n\n");
        }

        if (reasoningChain != null && !reasoningChain.isEmpty()) {
            sb.append("【推理链条】\n");
            for (int i = 0; i < reasoningChain.size(); i++) {
                sb.append(i + 1).append(". ").append(reasoningChain.get(i)).append("\n");
            }
            sb.append("\n");
        }

        if (deepThinkingContent != null && !deepThinkingContent.isBlank()) {
            sb.append("【深度思考】\n").append(deepThinkingContent).append("\n\n");
        }

        return sb.toString();
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建一个空轮次记录 (用于初始化)
     *
     * @param roundNumber 轮次编号
     * @return 空的 RoundRecord 实例
     */
    public static RoundRecord empty(int roundNumber) {
        return RoundRecord.builder()
                .roundNumber(roundNumber)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }
}
