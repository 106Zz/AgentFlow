package com.agenthub.api.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * ChatClient 配置
 */
@Configuration
public class ChatClientConfig {

    /**
     * 使用 Redis 作为对话记忆存储
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository(RedisTemplate<String, Object> redisTemplate) {
        return new com.agenthub.api.ai.config.RedisChatMemoryRepository(redisTemplate);
    }

    /**
     * 提供一个全局通用的、基础的 ChatClient Bean。
     * 供 RouterService、Worker 等不需要特殊记忆上下文的组件使用。
     * 
     * ChatUseCase 等高级组件会注入 ChatModel 自行构建"满配版" Client。
     */
    @Bean
    public ChatClient defaultChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
