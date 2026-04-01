package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.common.utils.RedisUtils;
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
 * RAG 查询结果缓存服务
 * <p>
 * 缓存知识库检索结果，减少重复查询，提升响应速度
 * </p>
 *
 * <h3>缓存策略：</h3>
 * <ul>
 *   <li>Key 设计: hash(query + userId + knowledgeId + topK)</li>
 *   <li>Value: PowerKnowledgeResult (JSON 序列化)</li>
 *   <li>TTL: 1 小时（可配置，带随机偏移防止雪崩）</li>
 * </ul>
 *
 * <h3>缓存防护机制：</h3>
 * <ul>
 *   <li>缓存击穿: 互斥锁 + 逻辑过期</li>
 *   <li>缓存穿透: 空值缓存（标记不存在的结果）</li>
 *   <li>缓存雪崩: 随机 TTL 偏移</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisUtils redisUtils;
    private final ObjectMapper objectMapper;

    private static final String CACHE_KEY_PREFIX = "rag:result:";
    private static final String LOCK_KEY_PREFIX = "rag:lock:";
    private static final String NULL_MARKER = "__NULL__";  // 空值标记

    @Value("${app.rag-cache.ttl-seconds:3600}")
    private long defaultTtlSeconds;

    @Value("${app.rag-cache.ttl-random-offset:300}")
    private long ttlRandomOffset;  // TTL 随机偏移量（防止雪崩）

    @Value("${app.rag-cache.lock-expire-seconds:10}")
    private long lockExpireSeconds;  // 锁过期时间

    /**
     * 缓存结果包装类（包含逻辑过期时间）
     */
    private static class CacheValue {
        public String jsonValue;
        public long softExpireAt;  // 逻辑过期时间戳
        public boolean isNull;      // 是否是空值（缓存穿透）

        public CacheValue() {}

        public CacheValue(String jsonValue, long softExpireAt, boolean isNull) {
            this.jsonValue = jsonValue;
            this.softExpireAt = softExpireAt;
            this.isNull = isNull;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > softExpireAt;
        }
    }

    /**
     * 获取缓存（带防护机制）
     *
     * @param query       查询内容
     * @param userId      用户ID
     * @param knowledgeId 知识库ID（可选）
     * @param topK        返回数量
     * @param loader      缓存未命中时的加载函数
     * @return 缓存结果
     */
    public PowerKnowledgeResult getOrLoad(
            String query,
            Long userId,
            Long knowledgeId,
            Integer topK,
            Supplier<PowerKnowledgeResult> loader) {

        String cacheKey = buildCacheKey(query, userId, knowledgeId, topK);

        try {
            // 1. 尝试从缓存获取
            CacheValue cachedValue = getCacheValue(cacheKey);
            
            if (cachedValue != null) {
                // 2. 检查是否是空值缓存（缓存穿透）
                if (cachedValue.isNull) {
                    log.debug("【RAG缓存】空值缓存命中: key={}", cacheKey);
                    return null;  // 返回 null 表示不存在
                }

                // 3. 检查是否即将过期（逻辑过期，触发异步刷新）
                if (cachedValue.isExpired()) {
                    log.debug("【RAG缓存】逻辑过期，异步刷新: key={}", cacheKey);
                    // 异步刷新缓存（不阻塞主流程）
                    asyncRefresh(cacheKey, userId, knowledgeId, topK, loader);
                    // 返回当前缓存值（不阻塞等待）
                }

                // 4. 返回缓存结果
                log.debug("【RAG缓存】命中: key={}", cacheKey);
                return deserialize(cachedValue.jsonValue);
            }

            // 5. 缓存未命中，使用互斥锁查库（防止缓存击穿）
            return executeWithLock(cacheKey, () -> {
                // 双重检查：获取锁后再查一次缓存
                CacheValue doubleCheck = getCacheValue(cacheKey);
                if (doubleCheck != null) {
                    if (doubleCheck.isNull) {
                        return null;
                    }
                    return deserialize(doubleCheck.jsonValue);
                }

                // 查 RAG
                PowerKnowledgeResult result = loader.get();

                // 存入缓存
                if (result != null && result.hasContent()) {
                    putWithRandomTtl(cacheKey, result);
                    log.debug("【RAG缓存】已存入: key={}", cacheKey);
                } else {
                    // 空结果也缓存（防止缓存穿透），使用较短 TTL
                    putNullValue(cacheKey);
                    log.debug("【RAG缓存】空值已存入: key={}", cacheKey);
                }

                return result;
            });

        } catch (Exception e) {
            log.warn("【RAG缓存】异常: key={}, error={}", cacheKey, e.getMessage());
            // 缓存异常时，直接调用加载函数
            return loader.get();
        }
    }

    /**
     * 获取缓存值（内部方法）
     */
    private CacheValue getCacheValue(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached == null) {
                return null;
            }

            String jsonStr;
            if (cached instanceof String) {
                jsonStr = (String) cached;
            } else {
                jsonStr = objectMapper.writeValueAsString(cached);
            }

            return objectMapper.readValue(jsonStr, CacheValue.class);
        } catch (Exception e) {
            log.warn("【RAG缓存】读取失败: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    /**
     * 存入缓存（带随机 TTL，防止雪崩）
     */
    private void putWithRandomTtl(String cacheKey, PowerKnowledgeResult result) {
        try {
            String jsonValue = objectMapper.writeValueAsString(result);
            
            // 计算随机 TTL（防止雪崩）
            long ttl = defaultTtlSeconds + (long) (Math.random() * ttlRandomOffset);
            
            // 逻辑过期时间 = 当前时间 + TTL
            long softExpireAt = System.currentTimeMillis() + ttl * 1000;
            
            CacheValue cacheValue = new CacheValue(jsonValue, softExpireAt, false);
            String cacheJson = objectMapper.writeValueAsString(cacheValue);
            
            redisTemplate.opsForValue().set(cacheKey, cacheJson, ttl, TimeUnit.SECONDS);
            log.debug("【RAG缓存】已存入: key={}, ttl={}s", cacheKey, ttl);
        } catch (JsonProcessingException e) {
            log.warn("【RAG缓存】存入失败: key={}, error={}", cacheKey, e.getMessage());
        }
    }

    /**
     * 存入空值缓存（防止缓存穿透）
     */
    private void putNullValue(String cacheKey) {
        try {
            // 空值缓存使用较短的 TTL
            long ttl = 300; // 5分钟
            
            long softExpireAt = System.currentTimeMillis() + ttl * 1000;
            CacheValue cacheValue = new CacheValue(NULL_MARKER, softExpireAt, true);
            String cacheJson = objectMapper.writeValueAsString(cacheValue);
            
            redisTemplate.opsForValue().set(cacheKey, cacheJson, ttl, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("【RAG缓存】空值存入失败: key={}", cacheKey, e);
        }
    }

    /**
     * 异步刷新缓存（不阻塞主流程）
     */
    private <T> void asyncRefresh(String cacheKey, Long userId, Long knowledgeId, 
                                  Integer topK, Supplier<T> loader) {
        // 异步执行，不等待结果
        Thread.startVirtualThread(() -> {
            try {
                executeWithLock(cacheKey + ":refresh", () -> {
                    // 查 RAG
                    T result = loader.get();
                    
                    // 重新存入缓存
                    if (result instanceof PowerKnowledgeResult pkr) {
                        if (pkr.hasContent()) {
                            putWithRandomTtl(cacheKey, pkr);
                        } else {
                            putNullValue(cacheKey);
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("【RAG缓存】异步刷新失败: key={}", cacheKey, e);
            }
        });
    }

    /**
     * 使用互斥锁执行（防止缓存击穿）
     */
    @SuppressWarnings("unchecked")
    private <T> T executeWithLock(String cacheKey, Supplier<T> action) {
        String lockKey = LOCK_KEY_PREFIX + cacheKey.hashCode();
        String requestId = UUID.randomUUID().toString();

        try {
            // 尝试获取锁（最多等待 3 秒）
            boolean locked = redisUtils.waitForLock(lockKey, requestId, lockExpireSeconds, 3, 50);
            
            if (!locked) {
                // 获取锁失败，短暂等待后重试获取缓存
                Thread.sleep(100);
                // 再次尝试获取缓存
                CacheValue cachedValue = getCacheValue(cacheKey);
                if (cachedValue != null && !cachedValue.isNull) {
                    return (T) deserialize(cachedValue.jsonValue);
                }
                // 仍然没有缓存，调用加载函数（不推荐，但防止死锁）
                return action.get();
            }

            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return action.get();
        } catch (Exception e) {
            log.warn("【RAG缓存】互斥锁执行失败: key={}", cacheKey, e);
            return action.get();
        } finally {
            redisUtils.unlock(lockKey, requestId);
        }
    }

    /**
     * 反序列化结果
     */
    private PowerKnowledgeResult deserialize(String jsonValue) {
        if (jsonValue == null || jsonValue.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonValue, PowerKnowledgeResult.class);
        } catch (JsonProcessingException e) {
            log.warn("【RAG缓存】反序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建缓存 Key
     */
    public String buildCacheKey(String query, Long userId, Long knowledgeId, Integer topK) {
        StringBuilder sb = new StringBuilder(CACHE_KEY_PREFIX);
        sb.append("q:").append(hash(query));
        sb.append("_u:").append(userId);
        if (knowledgeId != null) {
            sb.append("_k:").append(knowledgeId);
        }
        sb.append("_top:").append(topK != null ? topK : 5);
        return sb.toString();
    }

    /**
     * 简单的 hash 函数
     */
    private int hash(String str) {
        if (str == null || str.isEmpty()) {
            return 0;
        }
        return str.hashCode();
    }

    // ========== 兼容旧接口 ==========

    /**
     * 获取缓存结果（兼容旧接口）
     */
    public PowerKnowledgeResult get(String query, Long userId, Long knowledgeId, Integer topK) {
        return getOrLoad(query, userId, knowledgeId, topK, () -> null);
    }

    /**
     * 存入缓存（兼容旧接口）
     */
    public void put(String query, Long userId, Long knowledgeId, Integer topK, PowerKnowledgeResult result) {
        String cacheKey = buildCacheKey(query, userId, knowledgeId, topK);
        if (result != null && result.hasContent()) {
            putWithRandomTtl(cacheKey, result);
        } else {
            putNullValue(cacheKey);
        }
    }

    /**
     * 清除指定知识库的缓存
     */
    public long invalidateByKnowledgeId(Long knowledgeId) {
        String pattern = CACHE_KEY_PREFIX + "*_k:" + knowledgeId + "*";
        return deleteByPattern(pattern);
    }

    /**
     * 清除所有 RAG 缓存
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
                log.info("【RAG缓存】已清除缓存: {} 条", keys.size());
                return keys.size();
            }
        } catch (Exception e) {
            log.warn("【RAG缓存】清除失败: pattern={}, error={}", pattern, e.getMessage());
        }
        return 0;
    }

    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        String pattern = CACHE_KEY_PREFIX + "*";
        try {
            var keys = redisTemplate.keys(pattern);
            long size = keys != null ? keys.size() : 0;
            return new CacheStats(size, defaultTtlSeconds);
        } catch (Exception e) {
            log.warn("【RAG缓存】统计失败: error={}", e.getMessage());
            return new CacheStats(0, defaultTtlSeconds);
        }
    }

    public record CacheStats(long size, long ttlSeconds) {}
}
