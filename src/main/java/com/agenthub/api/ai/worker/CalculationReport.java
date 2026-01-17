package com.agenthub.api.ai.worker;


import java.util.List;

/**
 * 合规审查报告 (Output DTO)
 * 用于承载 Worker 执行完毕后的完整业务结论与证据链。
 */
public record CalculationReport(
        boolean isPassed,           // 审查是否通过
        String conclusion,          // 详细结论 (包含计算公式文本)
        List<String> evidenceSources // 证据来源 (e.g., ["2026交易规则.pdf"])
) {
}
