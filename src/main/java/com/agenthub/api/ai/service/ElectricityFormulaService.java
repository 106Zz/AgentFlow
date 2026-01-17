package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.calculator.DeviationInput;
import com.agenthub.api.ai.domain.calculator.DeviationResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class ElectricityFormulaService {

    /**
     * 计算偏差考核
     * @param threshold 动态阈值 (由 Worker 通过 RAG 获取后传入，e.g., 0.03)
     */
    public DeviationResult calculateDeviation(DeviationInput input, double threshold) {
        BigDecimal actual = BigDecimal.valueOf(input.actualLoad());
        BigDecimal plan = BigDecimal.valueOf(input.planLoad());
        BigDecimal thresholdVal = BigDecimal.valueOf(threshold);

        // 避免除以零
        if (plan.compareTo(BigDecimal.ZERO) == 0) {
            return new DeviationResult(0, 0, "Error: 计划电量为0，无法计算偏差率", false);
        }

        // 偏差率 = |实际 - 计划| / 计划
        BigDecimal diff = actual.subtract(plan).abs();
        BigDecimal ratio = diff.divide(plan, 4, RoundingMode.HALF_UP);

        if (ratio.compareTo(thresholdVal) <= 0) {
            // 修正：动态展示阈值百分比
            String msg = String.format("偏差率 %.2f%% ≤ %.0f%% (免责范围)",
                    ratio.doubleValue() * 100,
                    threshold * 100);
            return new DeviationResult(ratio.doubleValue(), 0.0, msg, true);
        } else {
            BigDecimal exemptLoad = plan.multiply(thresholdVal);
            BigDecimal penaltyLoad = diff.subtract(exemptLoad);

            // 价格取绝对值处理
            BigDecimal price = BigDecimal.valueOf(input.marketPrice()).abs();

            // 考核系数 (未来建议也提取为参数，目前暂定 1.0)
            BigDecimal coefficient = new BigDecimal("1.0");

            BigDecimal penalty = penaltyLoad.multiply(price).multiply(coefficient);

            // 4. 关键修正：公式字符串必须动态拼接，保证“所算即所显”
            // 否则 threshold 变了，这里显示的文字没变，就是严重的业务事故
            String formula = String.format(
                    "考核金额 = (|实际-计划| - %.0f%%×计划) × |价格| × %.1f \n(偏差率: %.2f%%, 考核电量: %.2f MWh)",
                    threshold * 100,            // 动态填充 3% 或 5%
                    coefficient.doubleValue(),
                    ratio.doubleValue() * 100,
                    penaltyLoad.doubleValue()
            );

            return new DeviationResult(
                    ratio.doubleValue(),
                    penalty.doubleValue(),
                    formula,
                    false
            );
        }
    }

}
