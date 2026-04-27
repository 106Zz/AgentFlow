package com.agenthub.api.agent_engine.service;

import com.agenthub.api.agent_engine.model.EvalDashboardVO;
import com.agenthub.api.common.core.page.PageResult;

/**
 * 评估大盘服务
 *
 * @author AgentHub
 * @since 2026-04-26
 */
public interface EvalDashboardService {

    /**
     * 获取 KPI 概览
     */
    EvalDashboardVO.EvalSummary getSummary();

    /**
     * 获取趋势数据
     */
    java.util.List<EvalDashboardVO.TrendPoint> getTrend(int days);

    /**
     * 获取错误归因分布
     */
    EvalDashboardVO.ErrorBreakdown getErrorBreakdown();

    /**
     * 分页查询 Bad Case
     */
    PageResult<EvalDashboardVO.BadCaseItem> getBadCases(int pageNum, int pageSize);

    /**
     * 获取 Case 详情
     */
    EvalDashboardVO.CaseDetail getCaseDetail(String caseId);
}
