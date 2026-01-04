package com.agenthub.api.ai.config;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * ChatClient 配置
 * 只提供基础 Bean，不创建 ChatClient（由 RagChatServiceImpl 动态创建）
 */
@Configuration
public class ChatClientConfig {

    /**
     * 使用 Redis 作为对话记忆存储
     * 替代 InMemoryChatMemoryRepository（内存会丢失）
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository(RedisTemplate<String, Object> redisTemplate) {
        return new com.agenthub.api.ai.config.RedisChatMemoryRepository(redisTemplate);
    }
    
    // 注意：不要在这里创建 ChatClient Bean！
}
