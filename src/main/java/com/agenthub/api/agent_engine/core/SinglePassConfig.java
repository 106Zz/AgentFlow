package com.agenthub.api.agent_engine.core;

import com.agenthub.api.agent_engine.config.DashScopeNativeService;
import com.agenthub.api.agent_engine.service.IntentRecognitionService;
import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.agenthub.api.agent_engine.service.ReflectionService;
import com.agenthub.api.agent_engine.capability.ToolRegistry;
import com.agenthub.api.prompt.service.ISysPromptService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 单次执行器配置
 *
 * @author AgentHub
 * @since 2026-02-07
 */
@Configuration
public class SinglePassConfig {

    @Bean
    public SinglePassExecutor singlePassExecutor(
            @Qualifier("workerChatClient") ChatClient workerClient,
            IntentRecognitionService intentRecognition,
            PowerKnowledgeService powerKnowledgeService,
            ToolRegistry toolRegistry,
            ReflectionService reflectionService,
            ChatMemoryRepository chatMemoryRepository,
            ISysPromptService sysPromptService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            DashScopeNativeService nativeService) {
        return new SinglePassExecutor(
                workerClient,
                intentRecognition,
                powerKnowledgeService,
                toolRegistry,
                reflectionService,
                chatMemoryRepository,
                sysPromptService,
                objectMapper,
                nativeService
        );
    }
}
