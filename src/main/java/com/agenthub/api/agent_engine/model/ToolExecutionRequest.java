package com.agenthub.api.agent_engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具执行请求
 * 封装 LLM 生成的工具调用参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionRequest {
    private String toolName;
    
    // 参数可能是 Map，也可能是 JSON 字符串，取决于具体实现。
    // 这里建议使用 Map<String, Object> 以便通用处理
    private Map<String, Object> arguments;
    
    private String originalCallId; // 对应 LLM 的 call_id，用于回传
}
