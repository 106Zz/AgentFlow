package com.agenthub.api.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 知识库版本号管理服务
 * 通过 Redis 维护知识库版本号，实现版本号驱动的缓存一致性：
 * 知识库更新时版本号递增，旧缓存 Key 自动失效
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeVersionService {

    private static final String VERSION_KEY_PREFIX = "kb:version:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 获取知识库当前版本号
     * 不存在时返回 1（首次查询的默认值)
     */
    public long getVersion(Long knowledgeBaseId) {
        try {
            Object val = redisTemplate.opsForValue().get(VERSION_KEY_PREFIX + knowledgeBaseId);
        return val != null ? Long.parseLong(val.toString()) : 1L;
        } catch (Exception e) {
            log.warn("【版本号】获取失败: kb={}, 降级为v1", knowledgeBaseId, e);
            return 1L;
        }
    }

    /**
     * 递增知识库版本号（原子操作）
     * 知识库内容变更时调用
     */
    public long incrementVersion(Long knowledgeBaseId) {
        try {
            Long newVersion = redisTemplate.opsForValue().increment(VERSION_KEY_PREFIX + knowledgeBaseId);
        log.info("【版本号】已递增: kb={}, newVersion={}", knowledgeBaseId, newVersion);
        return newVersion != null ? newVersion : 1L;
        } catch (Exception e) {
            log.warn("【版本号】递增失败: kb={}", knowledgeBaseId, e);
            return getVersion(knowledgeBaseId);
        }
    }
}
