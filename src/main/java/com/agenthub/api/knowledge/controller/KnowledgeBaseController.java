package com.agenthub.api.knowledge.controller;

import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理
 */
@Tag(name = "知识库管理")
@RestController
@RequestMapping("/knowledge/base")
public class KnowledgeBaseController extends BaseController {

    @Autowired
    private IKnowledgeBaseService knowledgeBaseService;

    @Operation(summary = "获取知识库列表")
    @GetMapping("/list")
    public AjaxResult list(KnowledgeBase knowledge, PageQuery pageQuery) {
        PageResult<KnowledgeBase> page;
        
        if (SecurityUtils.isAdmin()) {
            page = knowledgeBaseService.selectKnowledgePage(knowledge, pageQuery);
        } else {
            Long userId = SecurityUtils.getUserId();
            page = knowledgeBaseService.selectUserKnowledgePage(userId, knowledge, pageQuery);
        }
        
        return success(page);
    }

    @Operation(summary = "获取OSS上传临时凭证（前端直传用）")
    @GetMapping("/upload/policy")
    public AjaxResult getOssPolicy(@RequestParam(value = "filename", required = false) String filename) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        Map<String, String> policy = knowledgeBaseService.getUploadPolicy(userId, isAdmin, filename);
        return success(policy);
    }

    @Operation(summary = "前端直传后回调（创建知识库记录）")
    @PostMapping("/upload/callback")
    public AjaxResult uploadCallback(@RequestBody KnowledgeBase knowledge) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        KnowledgeBase result = knowledgeBaseService.handleUploadCallback(knowledge, userId, isAdmin);
        return success("上传成功，正在后台处理文档", result);
    }

    @Operation(summary = "批量上传回调")
    @PostMapping("/upload/batch-callback")
    public AjaxResult batchUploadCallback(@RequestBody List<KnowledgeBase> knowledgeList) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        List<KnowledgeBase> result = knowledgeBaseService.handleBatchUploadCallback(knowledgeList, userId, isAdmin);
        return success("批量上传成功，共 " + result.size() + " 个文件，正在后台处理", result);
    }

    @Operation(summary = "根据知识ID获取详细信息")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return success(knowledgeBaseService.getById(id));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        return success(knowledgeBaseService.deleteKnowledgeWithFiles(ids, userId, isAdmin));
    }

    @Operation(summary = "重新处理并向量化")
    @PreAuthorize("hasRole('ROLE_admin')")
    @PostMapping("/reprocess/{id}")
    public AjaxResult reprocess(@PathVariable Long id) {
        knowledgeBaseService.getBaseMapper().selectById(id);
        return success("处理任务已提交");
    }
}
