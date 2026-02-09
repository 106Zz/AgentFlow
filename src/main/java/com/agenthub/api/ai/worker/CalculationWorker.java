package com.agenthub.api.ai.worker;

import com.agenthub.api.ai.domain.calculator.DeviationInput;
import com.agenthub.api.ai.domain.calculator.DeviationResult;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.agenthub.api.ai.service.skill.ComplianceSkills;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 偏差计算 Worker
 * <p>职责：编排 RAG(查据) -> Skill(识意) -> Calculate(算账)</p>
 *
 * @author AgentHub
 * @since 2026-02-10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalculationWorker {

    private final PowerKnowledgeService powerKnowledgeService;
    private final ComplianceSkills complianceSkills;
    private final Executor executor;

    /**
     * 执行偏差考核审查流程
     *
     * @param userQuery 用户输入的自然语言，如 "计划500，实际480..."
     * @return 合规审查报告 (含证据链)
     */
    public CompletableFuture<List<WorkerResult>> executeReview(String userQuery) {
        return CompletableFuture.supplyAsync(() -> {
            log.info(">>>> [CalculationWorker] 开始执行偏差计算审查, query: {}", userQuery);

            // --- Step 0: 环境准备 ---
            String targetYear = complianceSkills.extractYearContext(userQuery);

            // --- Step 1: 查据 (Retrieve) ---
            double currentThreshold = retrieveThreshold(targetYear);

            // --- Step 2: 识意 (Extract) ---
            DeviationInput input = complianceSkills.extractDeviationParams(userQuery);

            // --- Step 3: 算账 (Calculate) ---
            DeviationResult calcResult = calculateDeviation(input, currentThreshold);

            // --- Step 4: 包装结果 ---
            WorkerResult result = new WorkerResult(
                    "偏差考核计算",
                    WorkerResult.WorkerType.CALCULATION,
                    calcResult.isExempt(),
                    calcResult.formulaUsed(),
                    calcResult.isExempt() ? "无需整改" : "建议核对实际用电量与申报计划",
                    List.of() // evidences - 如果需要可以后续添加
            );

            return List.of(result);

        }, executor);
    }

    /**
     * 查询免责阈值
     */
    private double retrieveThreshold(String targetYear) {
        String optimizedQuery = String.format("广东电力市场%s年偏差考核免责阈值", targetYear);
        PowerKnowledgeQuery ragQuery = new PowerKnowledgeQuery(
                optimizedQuery,
                1,
                targetYear,
                "BUSINESS"
        );

        try {
            PowerKnowledgeResult ragResult = powerKnowledgeService.retrieve(ragQuery);
            String contextText = ragResult.answer();
            if (contextText == null || contextText.isEmpty()) {
                contextText = String.join("\n", ragResult.rawContentSnippets());
            }
            return extractThresholdFromText(contextText);
        } catch (Exception e) {
            log.warn("[CalculationWorker] 查询阈值失败，使用默认值 3%: {}", e.getMessage());
            return 0.03;
        }
    }

    /**
     * 从 RAG 文本中提取百分比阈值
     */
    private double extractThresholdFromText(String text) {
        if (text == null || text.isEmpty()) return 0.03;

        Matcher m = Pattern.compile("(\\d+(\\.\\d+)?)%").matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1)) / 100.0;
            } catch (NumberFormatException e) {
                log.warn("阈值解析失败，使用默认值 3%");
            }
        }
        return 0.03;
    }

    /**
     * 计算偏差考核
     */
    private DeviationResult calculateDeviation(DeviationInput input, double threshold) {
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
            String msg = String.format("偏差率 %.2f%% ≤ %.0f%% (免责范围)",
                    ratio.doubleValue() * 100,
                    threshold * 100);
            return new DeviationResult(ratio.doubleValue(), 0.0, msg, true);
        } else {
            BigDecimal exemptLoad = plan.multiply(thresholdVal);
            BigDecimal penaltyLoad = diff.subtract(exemptLoad);
            BigDecimal price = BigDecimal.valueOf(input.marketPrice()).abs();
            BigDecimal coefficient = new BigDecimal("1.0");
            BigDecimal penalty = penaltyLoad.multiply(price).multiply(coefficient);

            String formula = String.format(
                    "考核金额 = (|实际-计划| - %.0f%%×计划) × |价格| × %.1f \n(偏差率: %.2f%%, 考核电量: %.2f MWh)",
                    threshold * 100,
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
