package com.agenthub.api.ai.tool.calculator;


public record DeviationResult(
        double deviationRatio,   // 偏差率
        double penaltyAmount,    // 考核金额
        String formulaUsed,      // 【关键】前端高亮显示的计算公式
        boolean isExempt         // 是否免责
) {
}
