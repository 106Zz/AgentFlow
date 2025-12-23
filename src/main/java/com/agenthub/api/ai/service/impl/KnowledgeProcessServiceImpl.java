package com.agenthub.api.ai.service.impl;

import com.agenthub.api.ai.service.IDocumentProcessService;
import com.agenthub.api.ai.service.IKnowledgeProcessService;
import com.agenthub.api.ai.service.IVectorStoreService;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识处理服务实现类
 */
@Service
public class KnowledgeProcessServiceImpl implements IKnowledgeProcessService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProcessServiceImpl.class);

    @Autowired
    private IKnowledgeBaseService knowledgeBaseService;

    @Autowired
    private IDocumentProcessService documentProcessService;

    @Autowired
    private IVectorStoreService vectorStoreService;

    @Override
    @Async("fileProcessExecutor")
    public void processKnowledgeAsync(Long knowledgeId) {
        log.info("开始异步处理知识库，ID: {}", knowledgeId);
        
        try {
            // 1. 获取知识库信息
            KnowledgeBase knowledge = knowledgeBaseService.getById(knowledgeId);
            if (knowledge == null) {
                throw new ServiceException("知识库不存在");
            }

            // 更新状态为处理中
            knowledge.setVectorStatus("1");
            knowledgeBaseService.updateById(knowledge);

            // 2. 解析文档
            File file = new File(knowledge.getFilePath());
            String content = documentProcessService.extractText(file, knowledge.getFileType());
            
            // 保存提取的内容
            knowledge.setContent(content);

            // 3. 生成摘要
            String summary = documentProcessService.generateSummary(content);
            knowledge.setSummary(summary);

            // 4. 文档分块
            List<String> chunks = documentProcessService.splitDocument(content, 500, 50);

            // 5. 创建文档对象（带元数据）
            List<Document> documents = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("knowledge_id", knowledgeId);
                metadata.put("user_id", knowledge.getUserId());
                metadata.put("is_public", knowledge.getIsPublic());
                metadata.put("file_name", knowledge.getFileName());
                metadata.put("file_type", knowledge.getFileType());
                metadata.put("category", knowledge.getCategory());
                metadata.put("title", knowledge.getTitle());
                metadata.put("chunk_index", i);
                
                documents.add(new Document(chunks.get(i), metadata));
            }

            // 6. 向量化并存储
            vectorStoreService.addDocuments(documents, knowledge.getUserId());

            // 7. 更新状态为已完成
            knowledge.setVectorStatus("2");
            knowledge.setVectorCount(chunks.size());
            knowledgeBaseService.updateById(knowledge);

            log.info("知识库处理完成，ID: {}, 向量数量: {}", knowledgeId, chunks.size());

        } catch (Exception e) {
            log.error("知识库处理失败，ID: {}", knowledgeId, e);
            
            // 更新状态为失败
            KnowledgeBase knowledge = knowledgeBaseService.getById(knowledgeId);
            if (knowledge != null) {
                knowledge.setVectorStatus("3");
                knowledge.setRemark("处理失败: " + e.getMessage());
                knowledgeBaseService.updateById(knowledge);
            }
        }
    }

    @Override
    @Async("fileProcessExecutor")
    public void batchProcessKnowledge(Long[] knowledgeIds) {
        log.info("开始批量处理知识库，数量: {}", knowledgeIds.length);
        
        for (Long knowledgeId : knowledgeIds) {
            processKnowledgeAsync(knowledgeId);
        }
    }
}
