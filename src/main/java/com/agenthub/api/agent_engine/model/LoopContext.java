package com.agenthub.api.agent_engine.model;

import lombok.Builder;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 循环上下文 (Immutable)
 * <p>存储循环过程中的状态、历史记录、配置和累积的上下文信息</p>
 */
@Builder
public record LoopContext(
        // ==================== 静态信息 ====================

        /**
         * 会话 ID
         */
        @JsonProperty("sessionId")
        String sessionId,

        /**
         * 用户 ID
         */
        @JsonProperty("userId")
        String userId,

        /**
         * 租户 ID
         */
        @JsonProperty("tenantId")
        String tenantId,

        /**
         * 用户原始问题
         * <p>在整个循环过程中保持不变，作为最终答案的目标</p>
         */
        @JsonProperty("originalQuery")
        String originalQuery,

        // ==================== 循环控制 ====================

        /**
         * 最大轮数
         * <p>防止无限循环，默认 3 轮</p>
         */
        @JsonProperty("maxRounds")
        int maxRounds,

        /**
         * 当前轮数 (从 0 开始，表示还未开始第一轮)
         */
        @JsonProperty("currentRound")
        int currentRound,

        /**
         * 循环开始时间
         */
        @JsonProperty("startTime")
        Long startTime,

        /**
         * 循环超时时间 (毫秒)
         * <p>默认 30 秒，防止长时间等待</p>
         */
        @JsonProperty("timeoutMs")
        Long timeoutMs,

        // ==================== RAG 上下文 ====================

        /**
         * RAG 检索到的文档列表
         */
        @JsonProperty("ragDocuments")
        List<Document> ragDocuments,

        /**
         * RAG 检索摘要 (用于传递给 LLM)
         */
        @JsonProperty("ragSummary")
        String ragSummary,

        // ==================== 历史记录 ====================

        /**
         * 历史轮次记录
         * <p>存储每轮的完整 RoundRecord</p>
         */
        @JsonProperty("roundHistory")
        List<RoundRecord> roundHistory,

        /**
         * 历史反思记录
         * <p>存储每轮的评估摘要，便于总结和传递给 LLM</p>
         */
        @JsonProperty("reflectionHistory")
        List<String> reflectionHistory,

        // ==================== 扩展属性 ====================

        /**
         * 扩展属性
         * <p>用于存储临时的会话状态或用户画像标签</p>
         */
        @JsonProperty("attributes")
        Map<String, Object> attributes
) {
    /**
     * 构造器，处理默认值
     */
    public LoopContext {
        // 空列表默认值
        if (ragDocuments == null) {
            ragDocuments = Collections.emptyList();
        }
        if (roundHistory == null) {
            roundHistory = new ArrayList<>();
        }
        if (reflectionHistory == null) {
            reflectionHistory = new ArrayList<>();
        }
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        // 默认时间
        if (startTime == null) {
            startTime = Instant.now().toEpochMilli();
        }
        // 默认超时 120 秒（多轮对话需要更长时间）
        if (timeoutMs == null) {
            timeoutMs = 120000L;
        }
    }

    // ==================== 工厂方法 ====================

    /**
     * 从 AgentContext 创建 LoopContext
     *
     * @param agentContext Agent 上下文
     * @return LoopContext 实例
     */
    public static LoopContext fromAgentContext(AgentContext agentContext) {
        return LoopContext.builder()
                .sessionId(agentContext.getSessionId())
                .userId(agentContext.getUserId())
                .tenantId(agentContext.getTenantId())
                .originalQuery(agentContext.getQuery())
                .maxRounds(3) // 默认 3 轮
                .currentRound(0)
                .build();
    }

    /**
     * 创建一个新的循环上下文
     *
     * @param sessionId     会话 ID
     * @param userId        用户 ID
     * @param originalQuery 原始问题
     * @return LoopContext 实例
     */
    public static LoopContext create(String sessionId, String userId, String originalQuery) {
        return LoopContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .originalQuery(originalQuery)
                .maxRounds(3)
                .currentRound(0)
                .build();
    }

    // ==================== 状态查询方法 ====================

    /**
     * 判断是否是第一轮
     *
     * @return true 如果是第一轮
     */
    public boolean isFirstRound() {
        return currentRound == 0 || roundHistory.isEmpty();
    }

    /**
     * 判断是否是最后一轮
     * <p>只有当 currentRound >= maxRounds 时才认为是最有一轮</p>
     * <p>例如：maxRounds=3 时，Round 3 才是最后一轮（Round 1、Round 2 会继续）</p>
     *
     * @return true 如果是最后一轮
     */
    public boolean isLastRound() {
        return currentRound >= maxRounds;
    }

    /**
     * 判断是否超时
     *
     * @return true 如果已超时
     */
    public boolean isTimeout() {
        long elapsed = Instant.now().toEpochMilli() - startTime;
        return elapsed > timeoutMs;
    }

    /**
     * 判断是否应该结束循环
     *
     * @return true 如果达到最大轮数或超时
     */
    public boolean shouldTerminate() {
        return currentRound >= maxRounds || isTimeout();
    }

    /**
     * 获取已用时间 (毫秒)
     *
     * @return 已用时间
     */
    public long getElapsedMs() {
        return Instant.now().toEpochMilli() - startTime;
    }

    /**
     * 获取上一轮记录
     *
     * @return 上一轮的 RoundRecord，如果不存在返回 null
     */
    public RoundRecord getLastRound() {
        if (roundHistory.isEmpty()) {
            return null;
        }
        return roundHistory.get(roundHistory.size() - 1);
    }

    /**
     * 获取上一轮的评估结果
     *
     * @return 上一轮的 EvaluationResult，如果不存在返回 null
     */
    public EvaluationResult getLastEvaluation() {
        RoundRecord last = getLastRound();
        return last != null ? last.evaluation() : null;
    }

    /**
     * 检查指定工具是否已在历史轮次中调用过
     * <p>用于防止同一轮对话中重复调用相同的检索工具（如 knowledge_search）</p>
     *
     * @param toolName 工具名称
     * @return true 如果该工具已在之前轮次调用过
     */
    public boolean hasToolBeenCalled(String toolName) {
        if (roundHistory == null || roundHistory.isEmpty()) {
            return false;
        }
        return roundHistory.stream()
                .flatMap(round -> {
                    List<ToolCall> calls = round.toolCalls();
                    return calls != null ? calls.stream() : java.util.stream.Stream.empty();
                })
                .anyMatch(call -> toolName.equals(call.toolName()));
    }

    /**
     * 获取所有已调用的工具名称
     * <p>用于调试和日志输出</p>
     *
     * @return 已调用工具名称的集合
     */
    public java.util.Set<String> getCalledToolNames() {
        if (roundHistory == null || roundHistory.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        return roundHistory.stream()
                .flatMap(round -> {
                    List<ToolCall> calls = round.toolCalls();
                    return calls != null ? calls.stream() : java.util.stream.Stream.empty();
                })
                .map(ToolCall::toolName)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ==================== 上下文转换方法 ====================

    /**
     * 进入下一轮
     * <p>返回 currentRound + 1 的新上下文</p>
     *
     * @return 新的 LoopContext 实例
     */
    public LoopContext nextRound() {
        return new LoopContext(
                sessionId, userId, tenantId, originalQuery,
                maxRounds, currentRound + 1, startTime, timeoutMs,
                ragDocuments, ragSummary, roundHistory, reflectionHistory, attributes
        );
    }

    /**
     * 添加一轮记录
     * <p>将 RoundRecord 添加到历史，并同步更新反思历史</p>
     *
     * @param record 轮次记录
     * @return 新的 LoopContext 实例
     */
    public LoopContext withRoundRecord(RoundRecord record) {
        List<RoundRecord> newHistory = new ArrayList<>(roundHistory);
        newHistory.add(record);

        // 如果有评估结果，也添加到反思历史中
        List<String> newReflectionHistory = new ArrayList<>(reflectionHistory);
        if (record.evaluation() != null) {
            newReflectionHistory.add(formatEvaluationReflection(record));
        }

        return new LoopContext(
                sessionId, userId, tenantId, originalQuery,
                maxRounds, currentRound, startTime, timeoutMs,
                ragDocuments, ragSummary, newHistory, newReflectionHistory, attributes
        );
    }

    /**
     * 设置 RAG 文档
     *
     * @param documents RAG 检索到的文档列表
     * @return 新的 LoopContext 实例
     */
    public LoopContext withRagDocuments(List<Document> documents) {
        return new LoopContext(
                sessionId, userId, tenantId, originalQuery,
                maxRounds, currentRound, startTime, timeoutMs,
                documents != null ? documents : Collections.emptyList(),
                ragSummary, roundHistory, reflectionHistory, attributes
        );
    }

    /**
     * 设置 RAG 摘要
     *
     * @param summary RAG 检索结果的文本摘要
     * @return 新的 LoopContext 实例
     */
    public LoopContext withRagSummary(String summary) {
        return new LoopContext(
                sessionId, userId, tenantId, originalQuery,
                maxRounds, currentRound, startTime, timeoutMs,
                ragDocuments, summary, roundHistory, reflectionHistory, attributes
        );
    }

    /**
     * 设置扩展属性
     *
     * @param key   属性键
     * @param value 属性值
     * @return 新的 LoopContext 实例
     */
    public LoopContext withAttribute(String key, Object value) {
        Map<String, Object> newAttrs = new HashMap<>(attributes);
        newAttrs.put(key, value);
        return new LoopContext(
                sessionId, userId, tenantId, originalQuery,
                maxRounds, currentRound, startTime, timeoutMs,
                ragDocuments, ragSummary, roundHistory, reflectionHistory, newAttrs
        );
    }

    /**
     * 设置最大轮数
     *
     * @param maxRounds 最大轮数
     * @return 新的 LoopContext 实例
     */
    public LoopContext withMaxRounds(int maxRounds) {
        return new LoopContext(
                sessionId, userId, tenantId, originalQuery,
                maxRounds, currentRound, startTime, timeoutMs,
                ragDocuments, ragSummary, roundHistory, reflectionHistory, attributes
        );
    }

    // ==================== 构建下一轮消息 ====================

    /**
     * 构建下一轮的反馈消息
     * <p>当上一轮评估失败时，构建反馈传递给下一轮</p>
     *
     * @return 反馈消息字符串，如果不需要反馈返回 null
     */
    public String buildFeedbackMessage() {
        EvaluationResult lastEval = getLastEvaluation();
        // 修复：只有评估失败才需要反馈（原逻辑错误：!isPassed 才返回 null）
        if (lastEval == null || lastEval.isPassed()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[上一轮回答未通过评估]\n");
        sb.append("原因: ").append(lastEval.getReason()).append("\n");
        if (lastEval.getSuggestion() != null && !lastEval.getSuggestion().isBlank()) {
            sb.append("建议: ").append(lastEval.getSuggestion()).append("\n");
        }
        sb.append("\n请根据以上反馈重新回答用户问题。");

        return sb.toString();
    }

    /**
     * 构建历史摘要
     * <p>将多轮历史压缩为摘要，避免 Token 爆炸</p>
     *
     * @return 历史摘要字符串
     */
    public String buildHistorySummary() {
        if (roundHistory.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【之前的尝试】\n");

        for (RoundRecord round : roundHistory) {
            sb.append(String.format("- 第%d轮", round.roundNumber()));

            // 工具调用情况
            if (round.hasToolCalls()) {
                sb.append(" | 调用工具: ");
                String tools = round.toolCalls().stream()
                        .map(ToolCall::toolName)
                        .collect(Collectors.joining(", "));
                sb.append(tools);
            }

            // 评估结果
            if (round.evaluation() != null) {
                sb.append(" | 评估: ").append(round.evaluation().isPassed() ? "通过" : "失败");
            }

            // 回答摘要 (截断长文本)
            if (round.generatedAnswer() != null && !round.generatedAnswer().isBlank()) {
                String answer = round.generatedAnswer();
                if (answer.length() > 100) {
                    answer = answer.substring(0, 100) + "...";
                }
                sb.append(" | 回答: ").append(answer);
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 构建完整的上下文提示
     * <p>整合 RAG 上下文、历史摘要和反馈消息，用于传递给 LLM</p>
     *
     * @return 完整的上下文提示字符串
     */
    public String buildContextPrompt() {
        StringBuilder sb = new StringBuilder();

        // RAG 上下文
        if (ragSummary != null && !ragSummary.isBlank()) {
            sb.append("【相关知识】\n").append(ragSummary).append("\n\n");
        }

        // 历史摘要
        if (!roundHistory.isEmpty()) {
            sb.append(buildHistorySummary()).append("\n");
        }

        // 反馈消息 (如果有)
        String feedback = buildFeedbackMessage();
        if (feedback != null) {
            sb.append(feedback).append("\n");
        }

        return sb.toString();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 格式化评估记录为反思历史
     *
     * @param record 轮次记录
     * @return 格式化的反思记录字符串
     */
    private String formatEvaluationReflection(RoundRecord record) {
        if (record.evaluation() == null) {
            return String.format("[第%d轮] 未评估", record.roundNumber());
        }
        return String.format("[第%d轮] %s - %s",
                record.roundNumber(),
                record.evaluation().isPassed() ? "通过" : "失败",
                record.evaluation().getReason() != null ? record.evaluation().getReason() : ""
        );
    }

    // ==================== 转换方法 ====================

    /**
     * 转换为 AgentContext (兼容现有代码)
     *
     * @return AgentContext 实例
     */
    public AgentContext toAgentContext() {
        return AgentContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .tenantId(tenantId)
                .query(originalQuery)
                .attributes(new HashMap<>(attributes))
                .build();
    }

    /**
     * 获取上下文统计信息
     *
     * @return 格式化的统计字符串
     */
    public String getStatistics() {
        return String.format(
                "LoopContext[round=%d/%d, elapsed=%dms, rounds=%d, tools=%d, evals=%s]",
                currentRound, maxRounds, getElapsedMs(),
                roundHistory.size(),
                roundHistory.stream().mapToInt(r -> r.toolCalls() != null ? r.toolCalls().size() : 0).sum(),
                roundHistory.stream().filter(RoundRecord::passedEvaluation).count()
        );
    }
}
