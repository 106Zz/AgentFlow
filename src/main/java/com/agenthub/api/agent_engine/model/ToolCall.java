package com.agenthub.api.agent_engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.Instant;

/**
 * 工具调用记录
 * <p>记录循环中对工具的调用信息，用于追踪和调试</p>
 *
 * @author AgentHub
 * @since 2026-02-03
 */
@Builder
public record ToolCall(
        /**
         * 工具名称
         */
        @JsonProperty("toolName")
        String toolName,

        /**
         * 调用参数 (JSON 字符串)
         */
        @JsonProperty("parameters")
        String parameters,

        /**
         * 调用时间戳 (毫秒)
         */
        @JsonProperty("timestamp")
        Long timestamp,

        /**
         * 调用 ID (用于关联调用和结果)
         */
        @JsonProperty("callId")
        String callId
) {
    /**
     * 构造器，处理默认值
     */
    public ToolCall {
        // 默认时间戳
        if (timestamp == null) {
            timestamp = Instant.now().toEpochMilli();
        }
        // 默认生成 callId
        if (callId == null) {
            callId = toolName + "-" + timestamp;
        }
    }

    /**
     * 快速创建工具调用记录
     *
     * @param toolName   工具名称
     * @param parameters 参数 JSON
     * @return ToolCall 实例
     */
    public static ToolCall of(String toolName, String parameters) {
        return ToolCall.builder()
                .toolName(toolName)
                .parameters(parameters)
                .build();
    }

    /**
     * 判断是否是有效的工具调用
     *
     * @return true 如果工具名称不为空
     */
    public boolean isValid() {
        return toolName != null && !toolName.isBlank();
    }
}
