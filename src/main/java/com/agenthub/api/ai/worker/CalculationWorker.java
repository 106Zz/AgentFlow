package com.agenthub.api.ai.worker;

import com.agenthub.api.ai.service.skill.ComplianceSkills;
import com.agenthub.api.ai.domain.calculator.DeviationInput;
import com.agenthub.api.ai.tool.calculator.ElectricityFormulaTool;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeTool;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应文档: v4_upgrade_phase2.md - 3.3 [Worker] 合规专工
 * 职责: 编排者。串联 RAG(查据) -> Skill(识意) -> Tool(算账)。
 */
@Slf4j
@Service
public class CalculationWorker {

    private final ElectricityFormulaTool formulaTool;
    private final PowerKnowledgeTool knowledgeTool;
    private final ComplianceSkills complianceSkills;
    private final Executor executor;

    public CalculationWorker(ElectricityFormulaTool formulaTool,
                             PowerKnowledgeTool knowledgeTool,
                             ComplianceSkills complianceSkills,
                             @Qualifier("agentWorkerExecutor") Executor agentWorkerExecutor) {
        this.formulaTool = formulaTool;
        this.knowledgeTool = knowledgeTool;
        this.complianceSkills = complianceSkills;
        this.executor = agentWorkerExecutor;
    }


    /**
     * 执行偏差考核审查流程
     * @param userQuery 用户输入的自然语言，如 "计划500，实际480..."
     * @return 合规审查报告 (含证据链)
     */
    public CompletableFuture<List<WorkerResult>> executeReview(String userQuery) {
        // 将整个串行逻辑包裹在 supplyAsync 中
        return CompletableFuture.supplyAsync(() -> {
            log.info(">>>> [Worker] 开始执行偏差计算审查, query: {}", userQuery);

            // --- Step 0: 环境准备 ---
            String targetYear = complianceSkills.extractYearContext(userQuery);

            // --- Step 1: 查据 (Retrieve) ---
            String optimizedQuery = String.format("广东电力市场%s年偏差考核免责阈值", targetYear);
            PowerKnowledgeQuery ragQuery = new PowerKnowledgeQuery(
                    optimizedQuery,
                    1,
                    "2026",
                    "BUSINESS" // 锁定商务类文档
            );
            PowerKnowledgeResult ragResult = knowledgeTool.retrieve(ragQuery);
            String contextText = ragResult.answer();
            if (contextText == null || contextText.isEmpty()) {
                contextText = String.join("\n", ragResult.rawContentSnippets());
            }
            double currentThreshold = extractThresholdFromText(contextText);

            // --- Step 2: 识意 (Extract) ---
            // 注意：ComplianceSkills 不需要改，它返回 DeviationInput 给 Worker 用是可以的
            DeviationInput input = complianceSkills.extractDeviationParams(userQuery);

            // --- Step 3: 算账 (Calculate) ---
            var calcResult = formulaTool.calculate(input, currentThreshold);

            // --- Step 4: 包装结果 (Adapt) ---

            // Change 3: 转换证据链，保留下载链接 (URL)
            List<WorkerResult.SourceEvidence> evidences = (ragResult.sources() == null) ? List.of() :
                    ragResult.sources().stream()
                            .map(doc -> new WorkerResult.SourceEvidence(doc.filename(), doc.downloadUrl())) // ✅ 这里保留了 URL
                            .toList();

            // Change 4: 构造统一的 WorkerResult
            WorkerResult result = new WorkerResult(
                    "偏差考核计算",                 // item
                    WorkerResult.WorkerType.CALCULATION,       // type: 标记为计算类
                    calcResult.isExempt(),        // isPassed
                    calcResult.formulaUsed(),     // riskDetails: 这里放计算公式详情
                    calcResult.isExempt() ? "无需整改" : "建议核对实际用电量与申报计划", // suggestion
                    evidences                     // evidences
            );

            return List.of(result); // 返回 List

        }, executor); // 指定在专用的 AI 线程池中运行
    }

    /**
     * 辅助逻辑：从 RAG 文本中提取百分比阈值
     * 在更复杂的 Worker 中，这也应该是一个独立的 Skill (如 RuleExtractionSkill)
     */
    private double extractThresholdFromText(String text) {
        if (text == null || text.isEmpty()) return 0.03; // 默认兜底

        // 匹配 "免责...x%" 或 "阈值...x%"
        Matcher m = Pattern.compile("(\\d+(\\.\\d+)?)%").matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1)) / 100.0;
            } catch (NumberFormatException e) {
                log.warn("阈值解析失败，使用默认值 3%");
            }
        }
        return 0.03; // 如果文档里没写数字，默认按 3% (兜底策略)
    }

}
