package com.agenthub.api.agent_engine.core.impl;

import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.core.loop.AgentLoopExecutor;
import com.agenthub.api.agent_engine.model.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * DeepSeekChatAgent - 一个基于 DeepSeek 模型的聊天代理实现
 * <p>V2.1: 逻辑重构，核心逻辑委托给 AgentLoopExecutor，实现了 "思考-行动-反思" 循环</p>
 */
@Slf4j
@Service
public class DeepSeekChatAgent implements ChatAgent {

    // ==================== 依赖注入组件 ====================

    private final AgentLoopExecutor agentLoopExecutor;

    public DeepSeekChatAgent(AgentLoopExecutor agentLoopExecutor) {
        this.agentLoopExecutor = agentLoopExecutor;
    }

    @Override
    public Flux<String> stream(AgentContext context) {
        log.info("[Agent] Delegating to LoopExecutor... Session: {}", context.getSessionId());
        return agentLoopExecutor.executeStream(context);
    }
}

