package com.agenthub.api.agent_engine.capability.impl;

import com.agenthub.api.agent_engine.capability.CapabilityResolver;
import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultCapabilityResolver implements CapabilityResolver {

    // 自动注入所有实现了 AgentTool 接口的 Bean
    private final List<AgentTool> allTools;

    @Override
    public List<AgentTool> resolveAvailableTools(AgentContext context) {
        // Phase 1: 暂时直接返回所有工具
        // 后续可以在这里加入基于 Tenant 或 User 的过滤逻辑
        log.debug("Resolving tools for user: {}", context.getUserId());
        return allTools;
    }

    @Override
    public boolean checkConstraint(AgentContext context, String toolName) {
        // Phase 1: 默认允许所有调用
        return true;
    }
}
