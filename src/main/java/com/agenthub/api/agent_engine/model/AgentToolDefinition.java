package com.agenthub.api.agent_engine.model;

import lombok.Builder;
import lombok.Data;

/**
 * 工具定义对象
 * <p>用于向 LLM 描述一个工具的能力和参数结构</p>
 *
 * @author AgentHub
 * @since 2026-02-07
 */
@Data
@Builder
public class AgentToolDefinition {
    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    private String parameterSchema;
    private boolean requiresConfirmation;
    private int costWeight;

    /**
     * 适用意图类型
     * <p>null 表示适用于所有意图</p>
     */
    private java.util.Set<IntentType> applicableIntents;

    /**
     * 是否为预检索工具
     * <p>预检索工具在 LLM 调用前执行</p>
     */
    private boolean isPreRetrievalTool;
}
