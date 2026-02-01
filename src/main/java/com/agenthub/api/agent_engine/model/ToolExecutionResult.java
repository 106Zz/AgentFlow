package com.agenthub.api.agent_engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果
 * 统一封装工具的返回，包括成功/失败状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private boolean success;
    
    // 工具的输出结果，通常是文本或 JSON 字符串，供 LLM 阅读
    private String output;
    
    // 结构化数据，用于前端渲染或后续处理（LLM 不一定看这个）
    private Object payload;
    
    private String errorMessage;
    
    public static ToolExecutionResult success(String output, Object payload) {
        return ToolExecutionResult.builder()
                .success(true)
                .output(output)
                .payload(payload)
                .build();
    }
    
    public static ToolExecutionResult failure(String errorMessage) {
        return ToolExecutionResult.builder()
                .success(false)
                .output("Tool execution failed: " + errorMessage)
                .errorMessage(errorMessage)
                .build();
    }
}
