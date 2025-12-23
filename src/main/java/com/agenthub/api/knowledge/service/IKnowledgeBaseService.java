package com.agenthub.api.knowledge.service;

import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.knowledge.domain.KnowledgeBase;

import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库 业务层
 */
public interface IKnowledgeBaseService extends IService<KnowledgeBase> {

    /**
     * 分页查询知识库列表
     */
    PageResult<KnowledgeBase> selectKnowledgePage(KnowledgeBase knowledge, PageQuery pageQuery);

    /**
     * 上传知识文件
     */
    KnowledgeBase uploadKnowledge(MultipartFile file, KnowledgeBase knowledge);

    /**
     * 处理文件并向量化
     */
    void processAndVectorize(Long knowledgeId);

    /**
     * 根据用户ID查询可访问的知识库
     */
    PageResult<KnowledgeBase> selectUserKnowledgePage(Long userId, KnowledgeBase knowledge, PageQuery pageQuery);
}
