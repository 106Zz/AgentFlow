package com.agenthub.api.agent_engine.model;

import lombok.Builder;
import lombok.Data;

/**
 * 工具定义对象
 * 用于向 LLM 描述一个工具的能力和参数结构
 */
@Data
@Builder
public class AgentToolDefinition {
    private String name;
    private String description;
    
    // JSON Schema 格式的参数描述
    private String parameterSchema;
    
    // 扩展字段：是否需要人工确认、成本权重等
    private boolean requiresConfirmation;
    private int costWeight;
}
