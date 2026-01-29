package com.agenthub.api.knowledge.service;

import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.dto.BatchPrepareResponse;

import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

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
     * 删除知识库（包括OSS文件和向量数据）
     * 异步执行，立即返回
     *
     * @param ids 知识库ID数组
     * @param userId 当前用户ID
     * @param isAdmin 是否管理员
     */
    void deleteKnowledgeWithFiles(Long[] ids, Long userId, boolean isAdmin);

    /**
     * 重新触发文档处理（用于状态卡住或失败时重试）
     * 会清理旧向量数据后重新处理
     *
     * @param ids 知识库ID数组
     * @param userId 当前用户ID
     * @param isAdmin 是否管理员
     */
    void reprocessKnowledge(Long[] ids, Long userId, boolean isAdmin);

    // ==================== 后台上传接口 ====================

    /**
     * 后台上传文件：接收文件 + 创建记录 + 发送 MQ 消息
     * 用户可立即离开，由后台异步处理
     *
     * @param fileName 文件名
     * @param title 标题
     * @param fileType 文件类型
     * @param fileSize 文件大小
     * @param tempFilePath 临时文件路径（已上传到服务器本地）
     * @param userId 当前用户ID
     * @param isAdmin 是否管理员
     * @return 知识库记录ID
     */
    Long submitBackgroundUpload(String fileName, String title, String fileType, Long fileSize,
                                String tempFilePath, Long userId, boolean isAdmin);

    /**
     * 批量后台上传文件
     *
     * @param requests 批量上传请求
     * @param userId 当前用户ID
     * @param isAdmin 是否管理员
     * @return 批量上传响应
     */
    BatchPrepareResponse batchBackgroundUpload(List<BackgroundUploadRequest> requests, Long userId, boolean isAdmin);

    /**
     * 后台上传请求
     */
    record BackgroundUploadRequest(
        String fileName,
        String title,
        String fileType,
        Long fileSize,
        String tempFilePath
    ) {}
}
