package com.agenthub.api.agent_engine.core.thinking;

import com.agenthub.api.agent_engine.config.DashScopeNativeService;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class StreamDeepThinkerConfig {

    @Bean
    @ConditionalOnBean(ChatMemoryRepository.class)
    @Lazy
    public StreamDeepThinker streamDeepThinker(
            DashScopeNativeService nativeService,
            ChatMemoryRepository chatMemoryRepository) {

        StreamDeepThinker.DashScopeNativeServiceWrapper wrapper =
            (model, messages, callback) -> nativeService.deepThinkStream(model, messages, callback);

        return new StreamDeepThinker(wrapper, chatMemoryRepository);
    }
}
