package com.agenthub.api.common.utils;

import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * 向量元数据工具类
 * 用于创建带用户隔离的文档元数据
 */
public class VectorMetadataUtils {

    /**
     * 创建文档元数据（用于向量存储）
     * 
     * @param knowledgeId 知识库ID
     * @param userId 用户ID（0表示全局）
     * @param isPublic 是否公开
     * @param fileName 文件名
     * @param fileType 文件类型
     * @param category 分类
     * @param tags 标签
     * @param title 标题
     * @param chunkIndex 块索引
     * @return 元数据Map
     */
    public static Map<String, Object> createMetadata(
            Long knowledgeId,
            Long userId,
            String isPublic,
            String fileName,
            String fileType,
            String category,
            String tags,
            String title,
            Integer chunkIndex) {

        Map<String, Object> metadata = new HashMap<>();

        // 核心字段：用于数据隔离和检索过滤
        metadata.put("knowledge_id", knowledgeId);
        metadata.put("user_id", userId);
        metadata.put("is_public", isPublic);

        // 辅助字段：用于展示和追溯
        metadata.put("filename", fileName);
        metadata.put("file_type", fileType);
        metadata.put("category", category);
        metadata.put("tags", tags);
        metadata.put("title", title);
        metadata.put("chunk_index", chunkIndex);

        return metadata;
    }

    /**
     * 创建带元数据的文档
     */
    public static Document createDocument(String content, Map<String, Object> metadata) {
        return new Document(content, metadata);
    }

    /**
     * 构建用户检索过滤表达式
     * 
     * @param userId 用户ID
     * @param isAdmin 是否为管理员
     * @return 过滤表达式（用于向量检索）
     */
    public static String buildUserFilter(Long userId, boolean isAdmin) {
        if (isAdmin) {
            // 管理员可以看到所有文档
            return null;
        }
        
        // 普通用户只能看到：
        // 1. 全局公开的文档（user_id = 0 AND is_public = '1'）
        // 2. 自己上传的文档（user_id = 当前用户ID）
        return String.format(
            "(user_id = 0 AND is_public = '1') OR user_id = %d",
            userId
        );
    }

    /**
     * 从Document中提取知识库ID
     */
    public static Long getKnowledgeId(Document document) {
        Object value = document.getMetadata().get("knowledge_id");
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        return null;
    }

    /**
     * 从Document中提取用户ID
     */
    public static Long getUserId(Document document) {
        Object value = document.getMetadata().get("user_id");
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        return null;
    }
}
