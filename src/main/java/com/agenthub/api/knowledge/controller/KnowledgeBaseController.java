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
import org.springframework.web.multipart.MultipartFile;

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
        
        // 管理员可以查看所有知识库，普通用户只能查看公开的和自己的
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

    @Operation(summary = "上传知识文件")
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("file") MultipartFile file,
                             @RequestParam(value = "title", required = false) String title,
                             @RequestParam(value = "category", required = false) String category,
                             @RequestParam(value = "tags", required = false) String tags,
                             @RequestParam(value = "isPublic", defaultValue = "0") String isPublic) {
        
        KnowledgeBase knowledge = new KnowledgeBase();
        knowledge.setTitle(title);
        knowledge.setCategory(category);
        knowledge.setTags(tags);
        knowledge.setIsPublic(isPublic);
        
        // 普通用户上传的知识归属于自己，管理员上传的归属于全局
        if (!SecurityUtils.isAdmin()) {
            knowledge.setUserId(SecurityUtils.getUserId());
        } else {
            knowledge.setUserId(0L); // 0表示全局知识库
        }
        
        KnowledgeBase result = knowledgeBaseService.uploadKnowledge(file, knowledge);
        return success("上传成功", result);
    }

    @Operation(summary = "修改知识库")
    @PreAuthorize("hasRole('ROLE_admin')")
    @PutMapping
    public AjaxResult edit(@RequestBody KnowledgeBase knowledge) {
        return success(knowledgeBaseService.updateById(knowledge));
    }

    @Operation(summary = "删除知识库")
    @PreAuthorize("hasRole('ROLE_admin')")
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return success(knowledgeBaseService.removeBatchByIds(java.util.Arrays.asList(ids)));
    }

    @Operation(summary = "重新处理并向量化")
    @PreAuthorize("hasRole('ROLE_admin')")
    @PostMapping("/reprocess/{id}")
    public AjaxResult reprocess(@PathVariable Long id) {
        knowledgeBaseService.processAndVectorize(id);
        return success("处理任务已提交");
    }
}
