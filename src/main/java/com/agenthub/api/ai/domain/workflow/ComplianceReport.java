package com.agenthub.api.ai.domain.workflow;


import java.util.List;

public record ComplianceReport(
        boolean overallPassed,      // 总体是否通过
        int riskCount,              // 风险项总数
        String auditStandard,       // 审查依据 (写死或动态生成)
        List<WorkerResult> details  // 详细列表 (用于前端渲染卡片)
) {
}
