package com.agenthub.api.agent_engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 工具调用记录（完整版）
 * <p>关联 ToolCall 和 ToolResult，用于 Judge 审计和 CaseSnapshot 冻结</p>
 *
 * @author AgentHub
 * @since 2026-02-09
 */
@Builder
public record ToolCallRecord(
        /**
         * 工具调用信息
         */
        @JsonProperty("toolCall")
        ToolCall toolCall,

        /**
         * 工具执行结果
         */
        @JsonProperty("toolResult")
        ToolResult toolResult
) {
    /**
     * 快速创建工具调用记录
     *
     * @param toolCall   工具调用信息
     * @param toolResult 工具执行结果
     * @return ToolCallRecord 实例
     */
    public static ToolCallRecord of(ToolCall toolCall, ToolResult toolResult) {
        return ToolCallRecord.builder()
                .toolCall(toolCall)
                .toolResult(toolResult)
                .build();
    }

    /**
     * 获取工具名称
     */
    public String toolName() {
        return toolCall != null ? toolCall.toolName() : null;
    }

    /**
     * 是否执行成功
     */
    public boolean isSuccess() {
        return toolResult != null && toolResult.success();
    }

    /**
     * 获取摘要信息（用于日志和 Prompt 构建）
     */
    public String getSummary() {
        if (toolCall == null) {
            return "[无效调用]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[工具: ").append(toolCall.toolName()).append("]");
        
        if (toolResult != null) {
            if (toolResult.success()) {
                String result = toolResult.result();
                if (result != null && result.length() > 200) {
                    result = result.substring(0, 200) + "...";
                }
                sb.append(" 成功: ").append(result);
            } else {
                sb.append(" 失败: ").append(toolResult.errorMessage());
            }
            sb.append(" (耗时: ").append(toolResult.durationMs()).append("ms)");
        } else {
            sb.append(" [未执行]");
        }

        return sb.toString();
    }
}
