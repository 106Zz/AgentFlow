package com.agenthub.api.knowledge.service;

import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.knowledge.domain.KnowledgeBase;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 知识库 业务层
 */
public interface IKnowledgeBaseService extends IService<KnowledgeBase> {

    /**
     * 分页查询知识库列表
     */
    PageResult<KnowledgeBase> selectKnowledgePage(KnowledgeBase knowledge, PageQuery pageQuery);

    /**
     * 根据用户ID查询可访问的知识库
     */
    PageResult<KnowledgeBase> selectUserKnowledgePage(Long userId, KnowledgeBase knowledge, PageQuery pageQuery);

    /**
     * 获取OSS上传凭证
     * 
     * @param userId 用户ID
     * @param isAdmin 是否管理员
     * @param filename 文件名
     * @return 上传凭证信息
     */
    Map<String, String> getUploadPolicy(Long userId, boolean isAdmin, String filename);

    /**
     * 处理前端直传OSS后的回调
     * 
     * @param knowledge 知识库对象
     * @param userId 当前用户ID
     * @param isAdmin 是否管理员
     * @return 创建的知识库记录
     */
    KnowledgeBase handleUploadCallback(KnowledgeBase knowledge, Long userId, boolean isAdmin);

    /**
     * 批量处理上传回调
     * 
     * @param knowledgeList 知识库列表
     * @param userId 当前用户ID
     * @param isAdmin 是否管理员
     * @return 创建的知识库记录列表
     */
    List<KnowledgeBase> handleBatchUploadCallback(List<KnowledgeBase> knowledgeList, Long userId, boolean isAdmin);

    /**
     * 删除知识库（包括OSS文件和向量数据）
     * 异步执行，立即返回
     *
     * @param ids 知识库ID数组
     * @param userId 当前用户ID
     * @param isAdmin 是否管理员
     */
    void deleteKnowledgeWithFiles(Long[] ids, Long userId, boolean isAdmin);

}
