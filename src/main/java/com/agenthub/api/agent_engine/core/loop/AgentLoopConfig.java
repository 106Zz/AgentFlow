package com.agenthub.api.agent_engine.core.loop;

import com.agenthub.api.agent_engine.capability.ToolRegistry;
import com.agenthub.api.agent_engine.config.DashScopeNativeService;
import com.agenthub.api.agent_engine.service.ReflectionService;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Agent 循环执行器配置
 * <p>Spring 配置类，负责创建和配置 AgentLoopExecutor Bean 及其依赖关系</p>
 *
 * <h3>配置职责：</h3>
 * <ul>
 *   <li><b>Bean 创建</b>: 创建 AgentLoopExecutor 单例 Bean</li>
 *   <li><b>依赖注入</b>: 注入所需的服务和组件</li>
 *   <li><b>循环依赖处理</b>: 使用 @Lazy 避免循环依赖问题</li>
 * </ul>
 *
 * <h3>依赖组件：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                   AgentLoopExecutor                          │
 * ├─────────────────────────────────────────────────────────────┤
 * │  DashScopeNativeService  → LLM 调用服务                      │
 * │  ToolRegistry             → 工具注册中心（预构建工具描述）    │
 * │  ReflectionService        → 反思评估服务                      │
 * │  ObjectMapper             → JSON 序列化                      │
 * │  ChatMemoryRepository     → 聊天记忆仓库                      │
 * │  ThreadPoolTaskExecutor   → 异步线程池                       │
 * │  ISysPromptService        → 系统提示词服务                    │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @author AgentHub
 * @since 2026-02-03
 */
@Slf4j
@Configuration
public class AgentLoopConfig {

    /**
     * 创建 AgentLoopExecutor Bean
     * <p>使用 @Lazy 延迟加载以避免可能的循环依赖问题</p>
     *
     * <h3>循环依赖说明：</h3>
     * <ul>
     *   <li>AgentLoopExecutor 依赖多个服务</li>
     *   <li>部分服务可能间接依赖 AgentLoopExecutor</li>
     *   <li>@Lazy 确保代理对象首先注入，实际调用时才初始化</li>
     * </ul>
     *
     * @param dashScopeNativeService DashScope 原生服务，用于 LLM 调用
     * @param toolRegistry           工具注册中心，预构建的工具描述缓存
     * @param reflectionService     反思服务，用于评估回答质量
     * @param objectMapper          JSON 序列化工具，用于解析工具调用参数
     * @param chatMemoryRepository  聊天记忆仓库，用于加载历史记录
     * @param agentWorkerExecutor   Agent 工作线程池，用于异步执行
     * @param sysPromptService      系统提示词服务，用于渲染动态提示词
     * @return 配置好的 AgentLoopExecutor 实例
     */
    @Bean
    @Lazy
    public AgentLoopExecutor agentLoopExecutor(
            DashScopeNativeService dashScopeNativeService,
            ToolRegistry toolRegistry,
            ReflectionService reflectionService,
            ObjectMapper objectMapper,
            ChatMemoryRepository chatMemoryRepository,
            ThreadPoolTaskExecutor agentWorkerExecutor,
            ISysPromptService sysPromptService) {

        log.info("[LoopConfig] Initializing AgentLoopExecutor with dependencies: " +
                "nativeService={}, toolRegistry={}, reflectionService={}, " +
                "chatMemoryRepository={}, agentWorkerExecutor={}",
                dashScopeNativeService.getClass().getSimpleName(),
                toolRegistry.getClass().getSimpleName(),
                reflectionService.getClass().getSimpleName(),
                chatMemoryRepository.getClass().getSimpleName(),
                agentWorkerExecutor.getClass().getSimpleName()
        );

        return new AgentLoopExecutor(
                dashScopeNativeService,
                toolRegistry,
                reflectionService,
                objectMapper,
                chatMemoryRepository,
                agentWorkerExecutor,
                sysPromptService
        );
    }
}
