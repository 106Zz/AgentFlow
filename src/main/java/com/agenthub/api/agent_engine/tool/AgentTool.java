package com.agenthub.api.agent_engine.tool;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;

/**
 * 原子工具接口
 * 所有具体能力（合规审查、偏差计算、RAG检索）都必须实现此接口
 * 
 * 设计原则：
 * 1. 原子性：对 LLM 而言是单次调用
 * 2. 无状态：不依赖外部会话状态，所需信息全在 Request 参数中
 */
public interface AgentTool {
    
    /**
     * 获取工具定义（元数据）
     */
    AgentToolDefinition getDefinition();
    
    /**
     * 执行工具逻辑
     * @param request 工具参数
     * @param context Agent 上下文 (用于获取 userId, docContent 等隐式环境信息)
     */
    ToolExecutionResult execute(ToolExecutionRequest request, AgentContext context);
}