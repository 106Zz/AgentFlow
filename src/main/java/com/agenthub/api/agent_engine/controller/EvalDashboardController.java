package com.agenthub.api.agent_engine.controller;

import com.agenthub.api.agent_engine.model.EvalDashboardVO;
import com.agenthub.api.agent_engine.service.EvalDashboardService;
import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.core.page.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评估大盘 Controller
 * <p>仅管理员可访问</p>
 *
 * @author AgentHub
 * @since 2026-04-26
 */
@Tag(name = "评估大盘")
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class EvalDashboardController extends BaseController {

    private final EvalDashboardService evalDashboardService;

    @Operation(summary = "获取 KPI 概览")
    @GetMapping("/summary")
    public AjaxResult getSummary() {
        return success(evalDashboardService.getSummary());
    }

    @Operation(summary = "获取趋势数据")
    @GetMapping("/trend")
    public AjaxResult getTrend(@RequestParam(defaultValue = "7") int days) {
        List<EvalDashboardVO.TrendPoint> trend = evalDashboardService.getTrend(days);
        return success(trend);
    }

    @Operation(summary = "获取错误归因分布")
    @GetMapping("/error-breakdown")
    public AjaxResult getErrorBreakdown() {
        return success(evalDashboardService.getErrorBreakdown());
    }

    @Operation(summary = "分页查询 Bad Case")
    @GetMapping("/bad-cases")
    public AjaxResult getBadCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResult<EvalDashboardVO.BadCaseItem> result = evalDashboardService.getBadCases(page, size);
        return success(result);
    }

    @Operation(summary = "获取 Case 详情")
    @GetMapping("/case/{caseId}")
    public AjaxResult getCaseDetail(@PathVariable String caseId) {
        EvalDashboardVO.CaseDetail detail = evalDashboardService.getCaseDetail(caseId);
        if (detail == null) {
            return error("Case 不存在");
        }
        return success(detail);
    }
}
