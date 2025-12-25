package com.agenthub.api.common.utils;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;

import java.util.HashMap;
import java.util.Map;

/**
 * 向量元数据工具类 (LangChain4j 版本)
 */
public class VectorMetadataUtilsV2 {

    /**
     * 构建文档元数据
     */
    public static Metadata buildMetadata(
            String filename,
            Long userId,
            Long knowledgeBaseId,
            String isPublic,
            Integer pageNumber,
            Integer chunkIndex
    ) {
        Map<String, Object> metadataMap = new HashMap<>();
        metadataMap.put("filename", filename);
        metadataMap.put("user_id", userId);
        metadataMap.put("knowledge_base_id", knowledgeBaseId);
        metadataMap.put("is_public", isPublic);
        
        if (pageNumber != null) {
            metadataMap.put("page_number", pageNumber);
        }
        if (chunkIndex != null) {
            metadataMap.put("chunk_index", chunkIndex);
        }
        
        return Metadata.from(metadataMap);
    }

    /**
     * 创建 TextSegment
     */
    public static TextSegment createTextSegment(String content, Metadata metadata) {
        return TextSegment.from(content, metadata);
    }

    /**
     * 从 TextSegment 获取元数据值
     */
    public static Object getMetadata(TextSegment segment, String key) {
        return segment.metadata(key);
    }
}
