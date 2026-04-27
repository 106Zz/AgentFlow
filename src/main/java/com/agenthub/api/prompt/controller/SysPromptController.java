package com.agenthub.api.prompt.controller;

import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.prompt.domain.dto.request.PromptCreateRequest;
import com.agenthub.api.prompt.domain.dto.request.PromptQueryRequest;
import com.agenthub.api.prompt.domain.dto.request.PromptUpdateRequest;
import com.agenthub.api.prompt.domain.entity.SysPromptCategory;
import com.agenthub.api.prompt.domain.entity.SysPromptVersion;
import com.agenthub.api.prompt.domain.vo.PromptVO;
import com.agenthub.api.prompt.service.ISysPromptCategoryService;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.agenthub.api.prompt.service.ISysPromptVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提示词管理 Controller
 * <p>仅管理员可访问</p>
 *
 * @author AgentHub
 * @since 2026-04-27
 */
@Tag(name = "提示词管理")
@RestController
@RequestMapping("/api/prompt")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class SysPromptController extends BaseController {

    private final ISysPromptService promptService;
    private final ISysPromptVersionService versionService;
    private final ISysPromptCategoryService categoryService;

    // ==================== 提示词 CRUD ====================

    @Operation(summary = "分页查询提示词列表")
    @GetMapping("/list")
    public AjaxResult list(PromptQueryRequest request) {
        PageResult<PromptVO> page = promptService.selectPage(request);
        return success(page);
    }

    @Operation(summary = "获取提示词详情")
    @GetMapping("/{id}")
    public AjaxResult getDetail(@PathVariable Long id) {
        PromptVO detail = promptService.getDetail(id);
        if (detail == null) {
            return error("提示词不存在");
        }
        return success(detail);
    }

    @Operation(summary = "创建提示词")
    @PostMapping
    public AjaxResult create(@RequestBody PromptCreateRequest request) {
        // 校验 code 唯一性
        if (promptService.getByCode(request.getPromptCode()) != null) {
            return error("提示词代码已存在: " + request.getPromptCode());
        }
        Long id = promptService.create(request);
        return success(id);
    }

    @Operation(summary = "更新提示词")
    @PutMapping("/{id}")
    public AjaxResult update(@PathVariable Long id, @RequestBody PromptUpdateRequest request) {
        request.setId(id);
        return success(promptService.update(request));
    }

    @Operation(summary = "删除提示词")
    @DeleteMapping("/{id}")
    public AjaxResult delete(@PathVariable Long id) {
        return success(promptService.delete(id));
    }

    // ==================== 状态管理 ====================

    @Operation(summary = "切换激活状态")
    @PutMapping("/{id}/active")
    public AjaxResult toggleActive(@PathVariable Long id, @RequestParam Boolean isActive) {
        return success(promptService.toggleActive(id, isActive));
    }

    @Operation(summary = "切换锁定状态")
    @PutMapping("/{id}/lock")
    public AjaxResult toggleLocked(@PathVariable Long id, @RequestParam Boolean isLocked) {
        return success(promptService.toggleLocked(id, isLocked));
    }

    // ==================== 版本管理 ====================

    @Operation(summary = "查询版本历史")
    @GetMapping("/{id}/versions")
    public AjaxResult listVersions(@PathVariable Long id) {
        List<SysPromptVersion> versions = versionService.listByPromptId(id);
        return success(versions);
    }

    @Operation(summary = "回滚到指定版本")
    @PostMapping("/{id}/rollback")
    public AjaxResult rollback(@PathVariable Long id, @RequestParam Long versionId) {
        return success(promptService.rollbackToVersion(id, versionId));
    }

    // ==================== 分类管理 ====================

    @Operation(summary = "查询分类树")
    @GetMapping("/categories")
    public AjaxResult listCategories() {
        List<SysPromptCategory> tree = categoryService.listTree();
        return success(tree);
    }
}
