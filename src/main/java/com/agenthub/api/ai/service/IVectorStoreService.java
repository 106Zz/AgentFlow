package com.agenthub.api.ai.service;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 向量存储服务接口
 */
public interface IVectorStoreService {

    /**
     * 添加文档到向量库
     * 
     * @param documents 文档列表
     * @param userId 用户ID（用于数据隔离）
     */
    void addDocuments(List<Document> documents, Long userId);

    /**
     * 根据用户权限检索相似文档
     * 
     * @param query 查询文本
     * @param userId 用户ID
     * @param isAdmin 是否为管理员
     * @param topK 返回数量
     * @param threshold 相似度阈值
     * @return 相似文档列表
     */
    List<Document> searchSimilar(String query, Long userId, boolean isAdmin, int topK, double threshold);

    /**
     * 删除知识库相关的向量数据
     * 
     * @param knowledgeId 知识库ID
     */
    void deleteByKnowledgeId(Long knowledgeId);
}
