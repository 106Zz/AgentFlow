package com.agenthub.api.knowledge.controller;

import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import com.agenthub.api.knowledge.dto.BatchConfirmRequest;
import com.agenthub.api.knowledge.dto.BatchConfirmResponse;
import com.agenthub.api.knowledge.dto.BatchPrepareRequest;
import com.agenthub.api.knowledge.dto.BatchPrepareResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库管理
 */
@Slf4j
@Tag(name = "知识库管理")
@RestController
@RequestMapping("/knowledge/base")
public class KnowledgeBaseController extends BaseController {

    @Autowired
    private IKnowledgeBaseService knowledgeBaseService;

    /**
     * 临时文件上传目录
     * v4.3 - 使用 src/main/resources/temp 目录
     * 注意：打包成JAR后此目录不可写，生产环境请配置外部路径
     */
    @Value("${upload.temp.dir:}")
    private String tempUploadDirConfig;

    /**
     * 获取临时文件上传目录（绝对路径）
     */
    private String getTempUploadDir() {
        if (tempUploadDirConfig != null && !tempUploadDirConfig.isEmpty()) {
            File dir = new File(tempUploadDirConfig);
            if (dir.isAbsolute()) {
                return tempUploadDirConfig;
            }
            return new File(System.getProperty("user.dir"), tempUploadDirConfig).getAbsolutePath() + File.separator;
        }
        // 默认：src/main/resources/temp
        return new File(System.getProperty("user.dir"), "src" + File.separator + "main" + File.separator + "resources" + File.separator + "temp" + File.separator).getAbsolutePath();
    }

    // ==================== 查询接口 ====================

    @Operation(summary = "获取知识库列表")
    @GetMapping("/list")
    public AjaxResult list(KnowledgeBase knowledge, PageQuery pageQuery) {
        if (knowledge.getTitle() != null && !knowledge.getTitle().isEmpty()) {
            log.info("搜索知识库: keyword={}", knowledge.getTitle());
        }

        PageResult<KnowledgeBase> page;

        if (SecurityUtils.isAdmin()) {
            page = knowledgeBaseService.selectKnowledgePage(knowledge, pageQuery);
        } else {
            Long userId = SecurityUtils.getUserId();
            page = knowledgeBaseService.selectUserKnowledgePage(userId, knowledge, pageQuery);
        }

        return success(page);
    }

    @Operation(summary = "根据知识ID获取详细信息")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return success(knowledgeBaseService.getById(id));
    }

    // ==================== 上传接口 ====================

    @Operation(summary = "重新触发文档处理（用于卡住或失败的记录）")
    @PostMapping("/upload/reprocess")
    public AjaxResult reprocessUpload(@RequestBody List<Long> ids) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();

        knowledgeBaseService.reprocessKnowledge(ids.toArray(new Long[0]), userId, isAdmin);
        return success("重新处理任务已提交，后台处理中");
    }

    // ==================== 后台上传接口 ====================

    @Operation(summary = "后台上传文件：用户可立即离开，由后台异步处理")
    @PostMapping(value = "/upload/background", consumes = "multipart/form-data")
    public AjaxResult backgroundUpload(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return error("文件不能为空");
            }

            Long userId = SecurityUtils.getUserId();
            boolean isAdmin = SecurityUtils.isAdmin();

            // 保存到临时目录
            String tempFilePath = saveToTempDir(file);

            // 提交后台上传
            Long knowledgeId = knowledgeBaseService.submitBackgroundUpload(
                    file.getOriginalFilename(),
                    null, // title 从文件名自动生成
                    getFileExtension(file.getOriginalFilename()),
                    file.getSize(),
                    tempFilePath,
                    userId,
                    isAdmin
            );

            return success(Map.of(
                    "knowledgeId", knowledgeId,
                    "message", "文件已提交，后台处理中"
            ));

        } catch (Exception e) {
            logger.error("后台上传失败", e);
            return error("上传失败: " + e.getMessage());
        }
    }

    @Operation(summary = "批量后台上传文件")
    @PostMapping(value = "/upload/background/batch", consumes = "multipart/form-data")
    public AjaxResult batchBackgroundUpload(@RequestParam("files") MultipartFile[] files) {
        try {
            if (files == null || files.length == 0) {
                return error("文件列表不能为空");
            }

            Long userId = SecurityUtils.getUserId();
            boolean isAdmin = SecurityUtils.isAdmin();

            // 构建批量上传请求
            List<IKnowledgeBaseService.BackgroundUploadRequest> requests = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    continue;
                }

                // 保存到临时目录
                String tempFilePath = saveToTempDir(file);

                IKnowledgeBaseService.BackgroundUploadRequest request =
                        new IKnowledgeBaseService.BackgroundUploadRequest(
                                file.getOriginalFilename(),
                                null, // title 从文件名自动生成
                                getFileExtension(file.getOriginalFilename()),
                                file.getSize(),
                                tempFilePath
                        );
                requests.add(request);
            }

            // 批量提交
            BatchPrepareResponse result = knowledgeBaseService.batchBackgroundUpload(requests, userId, isAdmin);

            return success(Map.of(
                    "submitted", requests.size(),
                    "result", result
            ));

        } catch (Exception e) {
            logger.error("批量后台上传失败", e);
            return error("批量上传失败: " + e.getMessage());
        }
    }

    /**
     * 保存文件到临时目录
     */
    private String saveToTempDir(MultipartFile file) throws IOException {
        // 获取临时目录（绝对路径）
        String tempDir = getTempUploadDir();
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);
        String uniqueFileName = UUID.randomUUID().toString() + "." + extension;
        File targetFile = new File(dir, uniqueFileName);

        // 保存文件
        file.transferTo(targetFile);

        log.info("文件已保存到临时目录: {}", targetFile.getAbsolutePath());
        return targetFile.getAbsolutePath();
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    // ==================== 删除接口 ====================

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();

        knowledgeBaseService.deleteKnowledgeWithFiles(ids, userId, isAdmin);
        return success("删除任务已提交，后台处理中");
    }
}
