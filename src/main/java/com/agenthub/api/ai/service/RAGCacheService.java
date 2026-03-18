package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

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
 *   <li>TTL: 1 小时（可配置）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_KEY_PREFIX = "rag:result:";
    private static final long DEFAULT_TTL_SECONDS = 3600; // 1小时

    /**
     * 获取缓存配置
     */
    private long getCacheTtlSeconds() {
        return DEFAULT_TTL_SECONDS;
    }

    /**
     * 构建缓存 Key
     *
     * @param query       查询内容
     * @param userId      用户ID
     * @param knowledgeId 知识库ID（可选）
     * @param topK        返回数量
     * @return 缓存 Key
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

    /**
     * 获取缓存结果
     *
     * @param query       查询内容
     * @param userId      用户ID
     * @param knowledgeId 知识库ID（可选）
     * @param topK        返回数量
     * @return 缓存结果，如果未命中返回 null
     */
    public PowerKnowledgeResult get(String query, Long userId, Long knowledgeId, Integer topK) {
        String cacheKey = buildCacheKey(query, userId, knowledgeId, topK);

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("【RAG缓存】命中: key={}", cacheKey);
                
                // 处理不同类型的缓存对象
                if (cached instanceof PowerKnowledgeResult) {
                    return (PowerKnowledgeResult) cached;
                } else if (cached instanceof String) {
                    // 如果缓存的是 JSON 字符串，需要反序列化
                    return objectMapper.readValue((String) cached, PowerKnowledgeResult.class);
                }
            }
        } catch (Exception e) {
            log.warn("【RAG缓存】读取失败: key={}, error={}", cacheKey, e.getMessage());
        }

        return null;
    }

    /**
     * 存入缓存
     *
     * @param query        查询内容
     * @param userId       用户ID
     * @param knowledgeId  知识库ID（可选）
     * @param topK         返回数量
     * @param result       检索结果
     */
    public void put(String query, Long userId, Long knowledgeId, Integer topK, PowerKnowledgeResult result) {
        String cacheKey = buildCacheKey(query, userId, knowledgeId, topK);

        try {
            // 序列化为 JSON 字符串存储
            String jsonValue = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(cacheKey, jsonValue, getCacheTtlSeconds(), TimeUnit.SECONDS);
            log.debug("【RAG缓存】已存入: key={}, ttl={}s", cacheKey, getCacheTtlSeconds());
        } catch (JsonProcessingException e) {
            log.warn("【RAG缓存】存入失败: key={}, error={}", cacheKey, e.getMessage());
        }
    }

    /**
     * 清除指定知识库的缓存
     *
     * @param knowledgeId 知识库ID
     * @return 清除的缓存数量
     */
    public long invalidateByKnowledgeId(Long knowledgeId) {
        String pattern = CACHE_KEY_PREFIX + "*_k:" + knowledgeId + "*";
        
        try {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("【RAG缓存】已清除知识库 {} 的缓存: {} 条", knowledgeId, keys.size());
                return keys.size();
            }
        } catch (Exception e) {
            log.warn("【RAG缓存】清除失败: knowledgeId={}, error={}", knowledgeId, e.getMessage());
        }

        return 0;
    }

    /**
     * 清除所有 RAG 缓存
     *
     * @return 清除的缓存数量
     */
    public long invalidateAll() {
        String pattern = CACHE_KEY_PREFIX + "*";

        try {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("【RAG缓存】已清除所有缓存: {} 条", keys.size());
                return keys.size();
            }
        } catch (Exception e) {
            log.warn("【RAG缓存】清除所有失败: error={}", e.getMessage());
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
            return new CacheStats(size, getCacheTtlSeconds());
        } catch (Exception e) {
            log.warn("【RAG缓存】统计失败: error={}", e.getMessage());
            return new CacheStats(0, getCacheTtlSeconds());
        }
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(long size, long ttlSeconds) {}
}
