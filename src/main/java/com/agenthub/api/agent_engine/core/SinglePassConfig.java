package com.agenthub.api.agent_engine.core;

import com.agenthub.api.agent_engine.config.LLMService;
import com.agenthub.api.agent_engine.service.IntentRecognitionService;
import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.agenthub.api.ai.service.gssc.GSSCService;
import com.agenthub.api.ai.service.LLMCacheService;
import com.agenthub.api.agent_engine.service.ReflectionService;
import com.agenthub.api.agent_engine.capability.ToolRegistry;
import com.agenthub.api.prompt.service.ICaseSnapshotService;
import com.agenthub.api.prompt.service.ISysPromptService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;

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
            ICaseSnapshotService caseSnapshotService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            LLMService llmService,
            GSSCService gscService,
            LLMCacheService llmCacheService,
            @Qualifier("judgeExecutor") Executor judgeExecutor,
            @Qualifier("agentWorkerExecutor") Executor agentWorkerExecutor,
            @Value("${app.llm.worker-model:deepseek-v3.2}") String workerModel) {
        return new SinglePassExecutor(
                workerClient,
                intentRecognition,
                powerKnowledgeService,
                toolRegistry,
                reflectionService,
                chatMemoryRepository,
                sysPromptService,
                caseSnapshotService,
                objectMapper,
                llmService,
                gscService,
                llmCacheService,
                judgeExecutor,
                agentWorkerExecutor,
                workerModel
        );
    }
}
