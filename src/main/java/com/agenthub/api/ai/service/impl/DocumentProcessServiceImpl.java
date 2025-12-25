package com.agenthub.api.ai.service.impl;

import com.agenthub.api.ai.utils.VectorStoreHelper;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.common.utils.OssUtils;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档处理服务实现
 * 负责从OSS下载文件、解析、向量化
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessServiceImpl {

    private final VectorStoreHelper vectorStoreHelper;
    private final OssUtils ossUtils;
    private final IKnowledgeBaseService knowledgeBaseService;

    /**
     * 异步处理知识库文档
     * 1. 从OSS下载文件
     * 2. 解析文档
     * 3. 向量化存储
     */
    @Async("fileProcessExecutor")
    public void processKnowledgeAsync(Long knowledgeId) {
        log.info("【异步任务】开始处理知识库，ID: {}", knowledgeId);
        
        try {
            // 1. 获取知识库信息
            KnowledgeBase knowledge = knowledgeBaseService.getById(knowledgeId);
            if (knowledge == null) {
                throw new ServiceException("知识库不存在");
            }

            // 更新状态为处理中
            knowledge.setVectorStatus("1");
            knowledgeBaseService.updateById(knowledge);

            // 2. 从OSS下载文件到本地临时目录
            String localPath = ossUtils.downloadToTemp(knowledge.getFilePath());
            File localFile = new File(localPath);

            try {
                // 3. 读取文件并处理
                byte[] fileBytes = new FileInputStream(localFile).readAllBytes();
                
                // 4. 构建额外元数据
                Map<String, Object> extraMetadata = new HashMap<>();
                extraMetadata.put("category", knowledge.getCategory());
                extraMetadata.put("tags", knowledge.getTags());
                extraMetadata.put("title", knowledge.getTitle());
                extraMetadata.put("file_type", knowledge.getFileType());

                // 5. 向量化处理（核心）
                int vectorCount = vectorStoreHelper.processAndStore(
                        fileBytes,
                        knowledge.getFileName(),
                        knowledge.getFileSize(),
                        knowledge.getId(),
                        knowledge.getUserId(),
                        knowledge.getIsPublic(),
                        extraMetadata
                );

                // 6. 更新状态为已完成
                knowledge.setVectorStatus("2");
                knowledge.setVectorCount(vectorCount);
                knowledgeBaseService.updateById(knowledge);

                log.info("【处理完成】知识库 ID: {}, 向量数量: {}", knowledgeId, vectorCount);

            } finally {
                // 7. 删除临时文件
                if (localFile.exists()) {
                    localFile.delete();
                    log.debug("临时文件已删除: {}", localPath);
                }
            }

        } catch (Exception e) {
            log.error("【处理失败】知识库 ID: {}", knowledgeId, e);
            
            // 更新状态为失败
            KnowledgeBase knowledge = knowledgeBaseService.getById(knowledgeId);
            if (knowledge != null) {
                knowledge.setVectorStatus("3");
                knowledge.setRemark("处理失败: " + e.getMessage());
                knowledgeBaseService.updateById(knowledge);
            }
        }
    }

    /**
     * 批量异步处理
     */
    @Async("fileProcessExecutor")
    public void batchProcessKnowledge(Long[] knowledgeIds) {
        log.info("【批量处理】开始处理 {} 个知识库", knowledgeIds.length);
        
        for (Long knowledgeId : knowledgeIds) {
            processKnowledgeAsync(knowledgeId);
        }
    }

    /**
     * 删除知识库的向量数据
     */
    public int deleteKnowledgeVectors(Long knowledgeId) {
        return vectorStoreHelper.deleteDocumentVectors(knowledgeId);
    }
}
