package com.agenthub.api.ai.domain.workflow;


import java.util.List;

public record WorkerResult(
        String item,                // 1. 检查项名称 (例如："投标保证金", "偏差考核免责")
        WorkerType workerType,      // 2. 来源工种 (用于前端分类展示)
        boolean isPassed,           // 3. 审查结论 (True=合规, False=有风险)
        String riskDetails,         // 4. 风险详情或计算过程描述
        String suggestion,          // 5. 修改建议 (AI 生成)
        List<SourceEvidence> evidences // 6. 证据链 (对应 TN-004 v2 的点击下载需求)
) {
    // 定义工种枚举
    public enum WorkerType {
        COMMERCIAL, // 商务标
        TECHNICAL,  // 技术标
        CALCULATION // 计算复核
    }

    // 定义证据对象 (文件名 + OSS链接)
    public record SourceEvidence(
            String filename,
            String downloadUrl
    ) {}
}
