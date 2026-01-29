package com.agenthub.api.knowledge.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.ai.service.impl.DocumentProcessServiceImpl;
import com.agenthub.api.ai.utils.VectorStoreHelper;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.knowledge.domain.DeleteResult;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.dto.BatchPrepareResponse;
import com.agenthub.api.knowledge.mapper.KnowledgeBaseMapper;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import com.agenthub.api.mq.domain.FileUploadMessage;
import com.agenthub.api.mq.producer.FileUploadProducer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements IKnowledgeBaseService {

    private final VectorStoreHelper vectorStoreHelper;
    private final DocumentProcessServiceImpl documentProcessService;

    @Override
    public PageResult<KnowledgeBase> selectKnowledgePage(KnowledgeBase knowledge, PageQuery pageQuery) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StrUtil.isNotEmpty(knowledge.getTitle()), KnowledgeBase::getTitle, knowledge.getTitle())
                .eq(StrUtil.isNotEmpty(knowledge.getCategory()), KnowledgeBase::getCategory, knowledge.getCategory())
                .eq(StrUtil.isNotEmpty(knowledge.getStatus()), KnowledgeBase::getStatus, knowledge.getStatus())
                .orderByDesc(KnowledgeBase::getCreateTime);

        IPage<KnowledgeBase> page = this.page(pageQuery.build(), wrapper);
        return PageResult.build(page);
    }

    @Override
    public PageResult<KnowledgeBase> selectUserKnowledgePage(Long userId, KnowledgeBase knowledge, PageQuery pageQuery) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();

        // 用户可以看到：全局公开的 + 自己的
        wrapper.and(w -> w
                .and(w1 -> w1.eq(KnowledgeBase::getUserId, 0).eq(KnowledgeBase::getIsPublic, "1"))
                .or()
                .eq(KnowledgeBase::getUserId, userId)
        );

        // 其他查询条件
        wrapper.like(StrUtil.isNotEmpty(knowledge.getTitle()), KnowledgeBase::getTitle, knowledge.getTitle())
                .eq(StrUtil.isNotEmpty(knowledge.getCategory()), KnowledgeBase::getCategory, knowledge.getCategory())
                .eq(StrUtil.isNotEmpty(knowledge.getStatus()), KnowledgeBase::getStatus, knowledge.getStatus())
                .orderByDesc(KnowledgeBase::getCreateTime);

        IPage<KnowledgeBase> page = this.page(pageQuery.build(), wrapper);
        return PageResult.build(page);
    }

    @Override
    @Async("fileProcessExecutor")
    public void deleteKnowledgeWithFiles(Long[] ids, Long userId, boolean isAdmin) {
        log.info("【异步删除】开始处理 {} 个知识库的删除请求，用户: {}", ids.length, userId);

        // 1. 权限检查（先检查权限，避免无效操作）
        List<Long> validIds = new ArrayList<>();
        for (Long id : ids) {
            KnowledgeBase knowledge = this.getById(id);
            if (knowledge == null) {
                log.warn("知识库不存在，跳过: {}", id);
                continue;
            }
            if (!isAdmin && !knowledge.getUserId().equals(userId)) {
                log.warn("无权删除该知识库: {}, 用户: {}", id, userId);
                continue;
            }
            validIds.add(id);
        }

        if (validIds.isEmpty()) {
            log.info("【异步删除】没有有效的知识库需要删除");
            return;
        }

        try {
            // 2. 删除核心数据（向量 + BM25 + 数据库记录，带事务）
            DeleteResult result = vectorStoreHelper.deleteKnowledgeData(validIds);

            // 3. 只有核心数据删除成功后，才清理 OSS 文件
            if (result.isAllSuccess()) {
                int cleanedCount = vectorStoreHelper.cleanupOssFiles(validIds);
                log.info("【异步删除】全部完成，成功删除 {} 个知识库，清理 {} 个OSS文件",
                        validIds.size(), cleanedCount);
            } else {
                log.warn("【异步删除】部分知识库删除失败，跳过OSS清理，failedIds={}",
                        result.getFailedKnowledgeIds());
            }
        } catch (Exception e) {
            log.error("【异步删除】删除过程发生异常", e);
        }
    }

    @Override
    @Async("fileProcessExecutor")
    public void reprocessKnowledge(Long[] ids, Long userId, boolean isAdmin) {
        log.info("【重新处理】开始处理 {} 个知识库，用户: {}", ids.length, userId);

        List<Long> validIds = new ArrayList<>();

        for (Long id : ids) {
            try {
                KnowledgeBase knowledge = this.getById(id);
                if (knowledge == null) {
                    log.warn("【重新处理】知识库不存在，跳过: {}", id);
                    continue;
                }

                // 权限检查
                if (!isAdmin && !knowledge.getUserId().equals(userId)) {
                    log.warn("【重新处理】无权操作，跳过: {}", id);
                    continue;
                }

                // 状态检查：只重新处理失败(4)、待处理(0)、待上传(0)的记录
                // 注意：处理中(2)的记录不重新处理，除非超时卡死
                String status = knowledge.getVectorStatus();
                if (!"0".equals(status) && !"1".equals(status) && !"2".equals(status) && !"4".equals(status)) {
                    log.info("【重新处理】状态为 {}，无需重新处理: {}", status, id);
                    continue;
                }

                // 检查是否长时间卡在状态2（处理中，超过30分钟认为卡死）
                if ("2".equals(status)) {
                    long minutesSinceUpdate = (System.currentTimeMillis() - knowledge.getUpdateTime().toInstant(ZoneOffset.of("+8")).toEpochMilli()) / (1000 * 60);
                    if (minutesSinceUpdate < 30) {
                        log.info("【重新处理】处理中但未超时，跳过: {}", id);
                        continue;
                    }
                    log.warn("【重新处理】检测到卡死记录（{}分钟未更新），清理旧数据: {}", minutesSinceUpdate, id);
                }

                // 清理旧向量数据（如果存在）
                try {
                    vectorStoreHelper.deleteKnowledgeData(List.of(id));
                    log.info("【重新处理】已清理旧向量数据: {}", id);
                } catch (Exception e) {
                    log.warn("【重新处理】清理旧向量数据失败，继续处理: {}", id, e);
                }

                // 重置状态为待处理
                knowledge.setVectorStatus("2");
                this.updateById(knowledge);

                validIds.add(id);

            } catch (Exception e) {
                log.error("【重新处理】处理单个记录失败，id: {}", id, e);
            }
        }

        if (!validIds.isEmpty()) {
            documentProcessService.batchProcessKnowledge(validIds.toArray(new Long[0]));
            log.info("【重新处理】已触发 {} 个文档的重新处理", validIds.size());
        } else {
            log.info("【重新处理】没有需要重新处理的文档");
        }
    }

    // ==================== 后台上传实现 ====================

    @Autowired
    private FileUploadProducer fileUploadProducer;

    @Override
    public Long submitBackgroundUpload(String fileName, String title, String fileType, Long fileSize,
                                       String tempFilePath, Long userId, boolean isAdmin) {
        // 1. 创建知识库记录
        KnowledgeBase knowledge = new KnowledgeBase();
        knowledge.setFileName(fileName);
        knowledge.setTitle(StrUtil.isNotBlank(title) ? title : fileName);
        knowledge.setFileType(StrUtil.isNotBlank(fileType) ? fileType : getFileType(fileName));
        knowledge.setFileSize(fileSize);
        knowledge.setVectorStatus("0"); // 待处理
        knowledge.setStatus("0");
        // v4.3 - 先存储临时文件路径，以便处理失败时可以重新处理
        // 上传到OSS成功后会更新为OSS路径
        knowledge.setFilePath(tempFilePath);
        knowledge.setUserId(userId);
        knowledge.setIsPublic("0");

        this.save(knowledge);
        log.info("【后台上传】创建知识库记录成功，ID: {}, 文件名: {}, 临时路径: {}", knowledge.getId(), fileName, tempFilePath);

        // 2. 发送 MQ 消息
        FileUploadMessage message = FileUploadMessage.builder()
                .knowledgeId(knowledge.getId())
                .userId(userId)
                .fileName(fileName)
                .title(title)
                .fileType(fileType)
                .fileSize(fileSize)
                .tempFilePath(tempFilePath)
                .isAdmin(isAdmin)
                .createTime(System.currentTimeMillis())
                .build();

        fileUploadProducer.sendUploadMessage(message);
        log.info("【后台上传】MQ消息已发送，知识库ID: {}", knowledge.getId());

        return knowledge.getId();
    }

    @Override
    public BatchPrepareResponse batchBackgroundUpload(List<BackgroundUploadRequest> requests, Long userId, boolean isAdmin) {
        if (requests == null || requests.isEmpty()) {
            throw new ServiceException("上传列表不能为空");
        }

        BatchPrepareResponse response = new BatchPrepareResponse();
        response.setFiles(new ArrayList<>());
        int successCount = 0;
        int failedCount = 0;

        for (BackgroundUploadRequest request : requests) {
            BatchPrepareResponse.FileUploadInfo fileInfo = new BatchPrepareResponse.FileUploadInfo();
            fileInfo.setFileName(request.fileName());

            try {
                // 提交后台上传
                Long knowledgeId = submitBackgroundUpload(
                        request.fileName(),
                        request.title(),
                        request.fileType(),
                        request.fileSize(),
                        request.tempFilePath(),
                        userId,
                        isAdmin
                );

                fileInfo.setKnowledgeId(knowledgeId);
                fileInfo.setStatus("submitted");
                fileInfo.setUploadPolicy(null); // 后台上传不需要前端上传凭证
                successCount++;

            } catch (Exception e) {
                fileInfo.setStatus("error");
                fileInfo.setErrorMsg(e.getMessage());
                failedCount++;
                log.error("【后台上传】提交失败，文件名: {}", request.fileName(), e);
            }

            response.getFiles().add(fileInfo);
        }

        log.info("【后台上传】批量提交完成，成功: {}, 失败: {}", successCount, failedCount);
        return response;
    }

    /**
     * 从文件名提取扩展名
     */
    private String getFileType(String fileName) {
        if (StrUtil.isBlank(fileName) || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }
}