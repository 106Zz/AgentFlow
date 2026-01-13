package com.agenthub.api.ai.tool.Compliance;


import java.util.List;

public record ComplianceCheckResult(
        boolean passed,                 // 是否通过
        double riskScore,               // 风险分 0-100
        List<ComplianceIssue> issues,   // 问题列表
        String summary                  // 总结建议
) {

    public record ComplianceIssue(
            String riskPoint,           // 违规点
            String severity,            // 严重程度 HIGH/MEDIUM/LOW
            String ruleReference,       // 依据的红头文件
            String suggestion           // 修改建议
    ) {}
}
