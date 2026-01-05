package com.agenthub.api.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@RequiredArgsConstructor
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "chat:memory:";
    private static final Duration TTL = Duration.ofDays(1);

    @Override
    public List<String> findConversationIds() {
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
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof List) {
                List<Message> messages = (List<Message>) value;
                log.debug("从Redis获取记忆成功: sessionId={}, 消息数={}", conversationId, messages.size());
                return messages;
            }
        } catch (Exception e) {
            log.error("Redis记忆反序列化失败，SessionId: {}。这通常是因为Message对象结构变化导致的。原数据将被忽略并重置。", conversationId, e);
            // 发生错误时清空，防止一直报错
            redisTemplate.delete(key);
            return new ArrayList<>();
        }
        return new ArrayList<>();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        
        String key = KEY_PREFIX + conversationId;
        try {
            redisTemplate.opsForValue().set(key, messages, TTL);
            log.debug("记忆已存入Redis: sessionId={}, 消息数={}", conversationId, messages.size());
        } catch (Exception e) {
            log.error("Redis记忆保存失败: sessionId={}", conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        redisTemplate.delete(key);
        log.debug("Redis记忆已删除: sessionId={}", conversationId);
    }
}