package com.agenthub.api.ai.service;

/**
 * 知识处理服务接口（异步）
 */
public interface IKnowledgeProcessService {

    /**
     * 异步处理知识库文件
     * 包括：文件解析、分块、向量化、存储
     * 
     * @param knowledgeId 知识库ID
     */
    void processKnowledgeAsync(Long knowledgeId);

    /**
     * 批量处理知识库文件
     * 
     * @param knowledgeIds 知识库ID列表
     */
    void batchProcessKnowledge(Long[] knowledgeIds);
}
