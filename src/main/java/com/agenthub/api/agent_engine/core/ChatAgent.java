package com.agenthub.api.agent_engine.core;

import com.agenthub.api.agent_engine.model.AgentContext;
import reactor.core.publisher.Flux;

/**
 * 聊天智能体核心接口 (Chat Agent Core)
 * 职责：作为大脑，负责 "Observe-Think-Act" 循环。
 * 
 * V2 升级：直接返回响应流，支持 SSE 打字机效果。
 */
public interface ChatAgent {
    
    /**
     * 执行 Agent 决策循环 (流式)
     * @param context 包含当前对话状态的上下文
     * @return SSE 数据流 (包含 thinking 过程和 final answer)
     */
    Flux<String> stream(AgentContext context);
}