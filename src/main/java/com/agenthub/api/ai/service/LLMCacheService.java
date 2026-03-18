package com.agenthub.api.ai.service;

import com.agenthub.api.common.utils.RedisUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * LLM 回答缓存服务
 * <p>
 * 缓存 LLM 最终回答（包括 RAG 回答和工具调用回答），减少重复调用 LLM
 * </p>
 *
 * <h3>缓存策略：</h3>
 * <ul>
 *   <li>Key 设计: hash(query + sessionId)</li>
 *   <li>Value: LLMAnswer (包含回答内容、来源)</li>
 *   <li>TTL: 30分钟（可配置）</li>
 *   <li>淘汰策略: Redis LRU (allkeys-lru)</li>
 * </ul>
 *
 * <h3>缓存防护：</h3>
 * <ul>
 *   <li>缓存击穿: 互斥锁</li>
 *   <li>缓存穿透: 空值缓存</li>
 *   <li>缓存雪崩: 随机 TTL</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisUtils redisUtils;
    private final ObjectMapper objectMapper;

    private static final String CACHE_KEY_PREFIX = "llm:answer:";
    private static final String LOCK_KEY_PREFIX = "llm:lock:";
    private static final String NULL_MARKER = "__NULL__";

    @Value("${app.llm.cache.ttl-seconds:1800}")
    private long defaultTtlSeconds;

    @Value("${app.llm.cache.ttl-random-offset:300}")
    private long ttlRandomOffset;

    @Value("${app.llm.cache.lock-expire-seconds:10}")
    private long lockExpireSeconds;

    /**
     * LLM 回答缓存对象
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LLMAnswer {
        public String answer;
        public String sourceType;  // RAG, TOOL, DIRECT
        public long cachedAt;

        public LLMAnswer() {}

        public LLMAnswer(String answer, String sourceType) {
            this.answer = answer;
            this.sourceType = sourceType;
            this.cachedAt = System.currentTimeMillis();
        }

        public boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - cachedAt > ttlMillis;
        }
    }

    /**
     * 获取缓存的回答
     *
     * @param query      用户问题
     * @param sessionId  会话ID（用于区分不同对话）
     * @param loader     缓存未命中时的加载函数
     * @return 缓存的回答
     */
    public String getOrLoad(
            String query,
            String sessionId,
            Supplier<String> loader) {

        String cacheKey = buildCacheKey(query, sessionId);

        try {
            // 1. 尝试获取缓存
            CacheValue cachedValue = getCacheValue(cacheKey);

            if (cachedValue != null) {
                // 2. 检查空值缓存
                if (cachedValue.isNull) {
                    log.debug("【LLM缓存】空值缓存命中: key={}", cacheKey);
                    return null;
                }

                // 3. 检查是否过期（逻辑过期，触发异步刷新）
                long ttlMillis = defaultTtlSeconds * 1000;
                if (cachedValue.isExpired(ttlMillis)) {
                    log.debug("【LLM缓存】逻辑过期，异步刷新: key={}", cacheKey);
                    asyncRefresh(cacheKey, sessionId, loader);
                    return deserializeAnswer(cachedValue.jsonValue);
                }

                // 4. 返回缓存
                log.debug("【LLM缓存】命中: key={}", cacheKey);
                return deserializeAnswer(cachedValue.jsonValue);
            }

            // 5. 缓存未命中，使用互斥锁查 LLM
            return executeWithLock(cacheKey, () -> {
                // 双重检查
                CacheValue doubleCheck = getCacheValue(cacheKey);
                if (doubleCheck != null) {
                    if (doubleCheck.isNull) {
                        return null;
                    }
                    return deserializeAnswer(doubleCheck.jsonValue);
                }

                // 调用 LLM 获取回答
                String answer = loader.get();

                // 缓存回答
                if (answer != null && !answer.isEmpty()) {
                    putWithRandomTtl(cacheKey, answer, "RAG");
                    log.debug("【LLM缓存】已存入: key={}", cacheKey);
                } else {
                    putNullValue(cacheKey);
                    log.debug("【LLM缓存】空回答已存入: key={}", cacheKey);
                }

                return answer;
            });

        } catch (Exception e) {
            log.warn("【LLM缓存】异常: key={}, error={}", cacheKey, e.getMessage());
            return loader.get();
        }
    }

    /**
     * 缓存 LLM 工具调用后的最终回答
     */
    public String getOrLoadWithTool(
            String query,
            String sessionId,
            String toolName,
            String toolResult,
            Supplier<String> loader) {

        String cacheKey = buildCacheKey(query, sessionId) + ":tool:" + toolName;

        try {
            CacheValue cachedValue = getCacheValue(cacheKey);

            if (cachedValue != null) {
                if (cachedValue.isNull) {
                    return null;
                }

                long ttlMillis = defaultTtlSeconds * 1000;
                if (cachedValue.isExpired(ttlMillis)) {
                    asyncRefreshWithTool(cacheKey, toolName, toolResult, loader);
                    return deserializeAnswer(cachedValue.jsonValue);
                }

                log.debug("【LLM缓存】命中(工具): key={}", cacheKey);
                return deserializeAnswer(cachedValue.jsonValue);
            }

            return executeWithLock(cacheKey, () -> {
                CacheValue doubleCheck = getCacheValue(cacheKey);
                if (doubleCheck != null) {
                    if (doubleCheck.isNull) {
                        return null;
                    }
                    return deserializeAnswer(doubleCheck.jsonValue);
                }

                String answer = loader.get();

                if (answer != null && !answer.isEmpty()) {
                    putWithRandomTtl(cacheKey, answer, "TOOL:" + toolName);
                } else {
                    putNullValue(cacheKey);
                }

                return answer;
            });

        } catch (Exception e) {
            log.warn("【LLM缓存】异常(工具): key={}, error={}", cacheKey, e.getMessage());
            return loader.get();
        }
    }

    /**
     * 获取缓存值
     */
    private CacheValue getCacheValue(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached == null) {
                return null;
            }

            String jsonStr = cached instanceof String ? (String) cached : objectMapper.writeValueAsString(cached);
            return objectMapper.readValue(jsonStr, CacheValue.class);
        } catch (Exception e) {
            log.warn("【LLM缓存】读取失败: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    /**
     * 存入缓存（随机 TTL）
     */
    private void putWithRandomTtl(String cacheKey, String answer, String sourceType) {
        try {
            long ttl = defaultTtlSeconds + (long) (Math.random() * ttlRandomOffset);
            long softExpireAt = System.currentTimeMillis() + ttl * 1000;

            LLMAnswer llmAnswer = new LLMAnswer(answer, sourceType);
            String jsonValue = objectMapper.writeValueAsString(llmAnswer);

            CacheValue cacheValue = new CacheValue(jsonValue, softExpireAt, false);
            String cacheJson = objectMapper.writeValueAsString(cacheValue);

            redisTemplate.opsForValue().set(cacheKey, cacheJson, ttl, TimeUnit.SECONDS);
            log.debug("【LLM缓存】已存入: key={}, ttl={}s, source={}", cacheKey, ttl, sourceType);
        } catch (JsonProcessingException e) {
            log.warn("【LLM缓存】存入失败: key={}", cacheKey, e);
        }
    }

    /**
     * 存入空值缓存
     */
    private void putNullValue(String cacheKey) {
        try {
            long ttl = 300; // 5分钟
            long softExpireAt = System.currentTimeMillis() + ttl * 1000;

            CacheValue cacheValue = new CacheValue(NULL_MARKER, softExpireAt, true);
            String cacheJson = objectMapper.writeValueAsString(cacheValue);

            redisTemplate.opsForValue().set(cacheKey, cacheJson, ttl, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("【LLM缓存】空值存入失败: key={}", cacheKey, e);
        }
    }

    /**
     * 异步刷新
     */
    private void asyncRefresh(String cacheKey, String sessionId, Supplier<String> loader) {
        Thread.startVirtualThread(() -> {
            try {
                executeWithLock(cacheKey + ":refresh", () -> {
                    String answer = loader.get();
                    if (answer != null && !answer.isEmpty()) {
                        putWithRandomTtl(cacheKey, answer, "RAG");
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("【LLM缓存】异步刷新失败: key={}", cacheKey, e);
            }
        });
    }

    private void asyncRefreshWithTool(String cacheKey, String toolName, String toolResult, Supplier<String> loader) {
        Thread.startVirtualThread(() -> {
            try {
                executeWithLock(cacheKey + ":refresh", () -> {
                    String answer = loader.get();
                    if (answer != null && !answer.isEmpty()) {
                        putWithRandomTtl(cacheKey, answer, "TOOL:" + toolName);
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("【LLM缓存】异步刷新失败(工具): key={}", cacheKey, e);
            }
        });
    }

    /**
     * 互斥锁执行
     */
    @SuppressWarnings("unchecked")
    private <T> T executeWithLock(String cacheKey, Supplier<T> action) {
        String lockKey = LOCK_KEY_PREFIX + Math.abs(cacheKey.hashCode());
        String requestId = UUID.randomUUID().toString();

        try {
            boolean locked = redisUtils.waitForLock(lockKey, requestId, lockExpireSeconds, 3, 50);

            if (!locked) {
                Thread.sleep(100);
                CacheValue cachedValue = getCacheValue(cacheKey);
                if (cachedValue != null && !cachedValue.isNull) {
                    return (T) deserializeAnswer(cachedValue.jsonValue);
                }
                return action.get();
            }

            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return action.get();
        } catch (Exception e) {
            log.warn("【LLM缓存】互斥锁执行失败: key={}", cacheKey, e);
            return action.get();
        } finally {
            redisUtils.unlock(lockKey, requestId);
        }
    }

    private String deserializeAnswer(String jsonValue) {
        if (jsonValue == null || jsonValue.isEmpty()) {
            return null;
        }
        try {
            LLMAnswer answer = objectMapper.readValue(jsonValue, LLMAnswer.class);
            return answer.answer;
        } catch (JsonProcessingException e) {
            log.warn("【LLM缓存】反序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 缓存值包装类
     */
    private static class CacheValue {
        public String jsonValue;
        public long softExpireAt;
        public boolean isNull;

        public CacheValue() {}

        public CacheValue(String jsonValue, long softExpireAt, boolean isNull) {
            this.jsonValue = jsonValue;
            this.softExpireAt = softExpireAt;
            this.isNull = isNull;
        }

        public boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() > softExpireAt;
        }
    }

    /**
     * 构建缓存 Key
     */
    public String buildCacheKey(String query, String sessionId) {
        return CACHE_KEY_PREFIX + "s:" + sessionId.hashCode() + "_q:" + query.hashCode();
    }

    /**
     * 清除指定会话的缓存
     */
    public long invalidateBySessionId(String sessionId) {
        String pattern = CACHE_KEY_PREFIX + "s:" + sessionId.hashCode() + "*";
        return deleteByPattern(pattern);
    }

    /**
     * 清除所有 LLM 缓存
     */
    public long invalidateAll() {
        String pattern = CACHE_KEY_PREFIX + "*";
        return deleteByPattern(pattern);
    }

    private long deleteByPattern(String pattern) {
        try {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("【LLM缓存】已清除: {} 条", keys.size());
                return keys.size();
            }
        } catch (Exception e) {
            log.warn("【LLM缓存】清除失败: pattern={}", pattern, e);
        }
        return 0;
    }

    /**
     * 获取缓存统计
     */
    public CacheStats getStats() {
        String pattern = CACHE_KEY_PREFIX + "*";
        try {
            var keys = redisTemplate.keys(pattern);
            long size = keys != null ? keys.size() : 0;
            return new CacheStats(size, defaultTtlSeconds);
        } catch (Exception e) {
            log.warn("【LLM缓存】统计失败: error={}", e.getMessage());
            return new CacheStats(0, defaultTtlSeconds);
        }
    }

    public record CacheStats(long size, long ttlSeconds) {}
}
