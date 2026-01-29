package com.agenthub.api.knowledge.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.ai.service.impl.DocumentProcessServiceImpl;
import com.agenthub.api.ai.utils.VectorStoreHelper;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.common.utils.OssUtils;
import com.agenthub.api.knowledge.domain.DeleteResult;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.mapper.KnowledgeBaseMapper;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final OssUtils ossUtils;

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
    public Map<String, String> getUploadPolicy(Long userId, boolean isAdmin, String filename) {
        // 定义上传目录：管理员上传到 public/，普通用户到 user/{userId}/
        String dir = isAdmin ? "knowledge/public/" : "knowledge/user/" + userId + "/";
        
        // 调用 OssUtils 生成临时上传策略
        return ossUtils.generateUploadPolicy(dir, filename);
    }

    @Override
    public KnowledgeBase handleUploadCallback(KnowledgeBase knowledge, Long userId, boolean isAdmin) {
        // 1. 参数校验
        if (StrUtil.isAllBlank(knowledge.getFileName(), knowledge.getFilePath())) {
            throw new ServiceException("文件名或OSS路径不能为空");
        }
        if (knowledge.getFileSize() == null || knowledge.getFileSize() <= 0) {
            throw new ServiceException("文件大小无效");
        }

        // 2. 验证文件是否真实存在（防篡改）
        if (!ossUtils.doesObjectExist(knowledge.getFilePath())) {
            throw new ServiceException("上传文件在OSS中不存在，请检查");
        }

        // 3. 权限处理
        if (isAdmin) {
            // 管理员可以指定公开或私有
            if ("1".equals(knowledge.getIsPublic())) {
                knowledge.setUserId(0L);  // 全局知识库
            } else {
                knowledge.setUserId(userId);
            }
        } else {
            // 普通用户强制私有
            knowledge.setUserId(userId);
            knowledge.setIsPublic("0");
        }

        // 4. 设置默认值
        if (StrUtil.isBlank(knowledge.getTitle())) {
            knowledge.setTitle(knowledge.getFileName());
        }
        if (StrUtil.isBlank(knowledge.getFileType())) {
            knowledge.setFileType(getFileType(knowledge.getFileName()));
        }
        knowledge.setVectorStatus("0");  // 未处理
        knowledge.setStatus("0");

        // 5. 保存记录
        boolean saved = this.save(knowledge);
        if (!saved) {
            throw new ServiceException("保存知识库记录失败");
        }

        // 6. 异步处理文档解析和向量化
        documentProcessService.processKnowledgeAsync(knowledge.getId());

        log.info("知识库上传成功，ID: {}, 文件: {}, 用户: {}", 
                knowledge.getId(), knowledge.getFileName(), userId);

        return knowledge;
    }

    @Override
    public List<KnowledgeBase> handleBatchUploadCallback(List<KnowledgeBase> knowledgeList, Long userId, boolean isAdmin) {
        if (knowledgeList == null || knowledgeList.isEmpty()) {
            throw new ServiceException("上传列表不能为空");
        }

        List<KnowledgeBase> savedList = new ArrayList<>();
        List<Long> ids = new ArrayList<>();

        for (KnowledgeBase knowledge : knowledgeList) {
            try {
                KnowledgeBase saved = handleUploadCallback(knowledge, userId, isAdmin);
                savedList.add(saved);
                ids.add(saved.getId());
            } catch (Exception e) {
                log.error("批量上传处理失败，文件: {}", knowledge.getFileName(), e);
                // 继续处理其他文件
            }
        }

        // 批量处理
        if (!ids.isEmpty()) {
            documentProcessService.batchProcessKnowledge(ids.toArray(new Long[0]));
        }

        log.info("批量上传成功，共 {} 个文件", savedList.size());
        return savedList;
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

    /**
     * 获取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return switch (extension) {
            case "pdf" -> "pdf";
            case "doc", "docx" -> "word";
            case "xls", "xlsx" -> "excel";
            case "ppt", "pptx" -> "ppt";
            case "jpg", "jpeg", "png", "gif", "bmp" -> "image";
            case "txt" -> "text";
            case "md" -> "markdown";
            default -> "other";
        };
    }
}
