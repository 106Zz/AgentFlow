package com.agenthub.api.agent_engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * 工具执行结果
 * <p>封装工具调用的执行结果，包含成功/失败状态和返回数据</p>
 *
 * @author AgentHub
 * @since 2026-02-03
 */
@Builder
public record ToolResult(
        /**
         * 工具名称
         */
        @JsonProperty("toolName")
        String toolName,

        /**
         * 是否执行成功
         */
        @JsonProperty("success")
        boolean success,

        /**
         * 结果内容 (成功时)
         */
        @JsonProperty("result")
        String result,

        /**
         * 错误信息 (失败时)
         */
        @JsonProperty("errorMessage")
        String errorMessage,

        /**
         * 执行耗时 (毫秒)
         */
        @JsonProperty("durationMs")
        long durationMs,

        /**
         * 关联的调用 ID (对应 ToolCall.callId)
         */
        @JsonProperty("callId")
        String callId
) {
    /**
     * 创建成功结果
     *
     * @param toolName    工具名称
     * @param result      返回结果
     * @param durationMs  执行耗时
     * @return ToolResult 实例
     */
    public static ToolResult success(String toolName, String result, long durationMs) {
        return ToolResult.builder()
                .toolName(toolName)
                .success(true)
                .result(result)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 创建失败结果
     *
     * @param toolName     工具名称
     * @param errorMessage 错误信息
     * @param durationMs   执行耗时
     * @return ToolResult 实例
     */
    public static ToolResult failure(String toolName, String errorMessage, long durationMs) {
        return ToolResult.builder()
                .toolName(toolName)
                .success(false)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 创建失败结果 (带调用 ID 关联)
     *
     * @param toolName     工具名称
     * @param errorMessage 错误信息
     * @param durationMs   执行耗时
     * @param callId       调用 ID
     * @return ToolResult 实例
     */
    public static ToolResult failure(String toolName, String errorMessage, long durationMs, String callId) {
        return ToolResult.builder()
                .toolName(toolName)
                .success(false)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .callId(callId)
                .build();
    }

    /**
     * 获取结果摘要 (用于日志或上下文传递)
     * <p>长内容会被截断到 200 字符</p>
     *
     * @return 格式化的结果摘要字符串
     */
    public String getSummary() {
        if (success) {
            String content = result == null ? "" : result;
            if (content.length() > 200) {
                content = content.substring(0, 200) + "...";
            }
            return "[" + toolName + " 执行成功] " + content;
        } else {
            return "[" + toolName + " 执行失败] " + (errorMessage != null ? errorMessage : "未知错误");
        }
    }
}
