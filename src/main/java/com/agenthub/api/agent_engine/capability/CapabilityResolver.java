package com.agenthub.api.agent_engine.capability;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.tool.AgentTool;

import java.util.List;

/**
 * 能力解析器 (Capability Resolver)
 * 职责：作为 System Guard，决定当前会话允许使用哪些工具。
 * 
 * 区别于 Router：
 * 1. 它不做自然语言理解 (NLU)
 * 2. 它只基于系统规则 (租户权限、套餐等级、风控策略) 进行硬过滤
 */
public interface CapabilityResolver {
    
    /**
     * 解析当前可用的工具列表
     * @param context Agent 上下文
     * @return 允许使用的工具实例列表
     */
    List<AgentTool> resolveAvailableTools(AgentContext context);
    
    /**
     * 检查工具调用的前置约束（如：频率限制、余额检查）
     * @param context Agent 上下文
     * @param toolName 目标工具名称
     * @return 是否允许调用
     */
    boolean checkConstraint(AgentContext context, String toolName);
}
