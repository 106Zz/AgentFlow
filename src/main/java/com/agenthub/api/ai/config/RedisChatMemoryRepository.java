package com.agenthub.api.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自定义 Redis 会话记忆存储
 * 用于替代 Spring AI 可能缺失的 RedisChatMemoryRepository
 */
@RequiredArgsConstructor
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis Key 前缀
    private static final String KEY_PREFIX = "chat:memory:";
    // 会话过期时间 (例如 1 天)
    private static final Duration TTL = Duration.ofDays(1);

    @Override
    public List<String> findConversationIds() {
        // 注意：keys 命令在生产环境大量 key 时可能有性能问题，需谨慎使用
        // 这里为了完整实现接口而提供
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null) {
            return Collections.emptyList();
        }
        return keys.stream()
                .map(key -> key.replace(KEY_PREFIX, ""))
                .collect(Collectors.toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Message> findByConversationId(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        Object value = redisTemplate.opsForValue().get(key);
        
        if (value instanceof List) {
            return (List<Message>) value;
        }
        return new ArrayList<>();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;
        redisTemplate.opsForValue().set(key, messages, TTL);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        redisTemplate.delete(key);
    }
}