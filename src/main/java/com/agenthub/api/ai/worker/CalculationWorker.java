package com.agenthub.api.ai.worker;

import com.agenthub.api.ai.service.skill.ComplianceSkills;
import com.agenthub.api.ai.tool.calculator.DeviationInput;
import com.agenthub.api.ai.tool.calculator.ElectricityFormulaTool;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对应文档: v4_upgrade_phase2.md - 3.3 [Worker] 合规专工
 * 职责: 编排者。串联 RAG(查据) -> Skill(识意) -> Tool(算账)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalculationWorker {

    private final ElectricityFormulaTool formulaTool;
    private final PowerKnowledgeTool knowledgeTool;

    // 2. 注入认知技能 (中间层能力)
    private final ComplianceSkills complianceSkills;

    /**
     * 执行偏差考核审查流程
     * @param userQuery 用户输入的自然语言，如 "计划500，实际480..."
     * @return 合规审查报告 (含证据链)
     */
    public CalculationReport executeReview(String userQuery) {
        log.info(">>>> [Worker] 开始执行合规审查, query: {}", userQuery);

        String targetYear = complianceSkills.extractYearContext(userQuery);
        log.info(">>>> [Worker] 上下文锁定年份: {}", targetYear);

        // ----------------------------------------------------------
        // Step 1: 查据 (Retrieve) - 问 RAG 现在的规则是什么？
        // ----------------------------------------------------------
        // 关键变更：利用你刚修改的 PowerKnowledgeQuery，指定 category="BUSINESS"
        // 这样可以避免搜到“频率偏差”等技术文档，只看“商务考核”
        String optimizedQuery = String.format("广东电力市场%s年偏差考核免责阈值", targetYear);

        PowerKnowledgeQuery ragQuery = new PowerKnowledgeQuery(
                optimizedQuery,
                1,     // topK
                "2026",// yearFilter (假设默认查2026，实际可由Skill提取)
                "BUSINESS" // category (核心！物理隔离非商务文档)
        );

        log.info(">>>> [Worker] 调用 RAG 检索规则, category=BUSINESS");
        PowerKnowledgeResult ragResult = knowledgeTool.retrieve(ragQuery);

        // 解析 RAG 返回的文本，找到那个关键的数字 (e.g., 3%)
        // 这一步体现了 Worker 的"粘合剂"作用：把非结构化文本转为结构化参数
        String contextText = ragResult.answer();

        // 防御性编程：如果 answer 为空，则把原始片段拼起来读
        if (contextText == null || contextText.isEmpty()) {
            contextText = String.join("\n", ragResult.rawContentSnippets());
        }

        // 现在从这段文本里去提取 "3%"
        double currentThreshold = extractThresholdFromText(contextText);


        // ----------------------------------------------------------
        // Step 2: 识意 (Extract) - 问 LLM 用户的数据是多少？
        // ----------------------------------------------------------
        log.info(">>>> [Worker] 调用 Skill 提取参数");
        DeviationInput input = complianceSkills.extractDeviationParams(userQuery);
        log.info(">>>> [Worker] 参数提取完成: {}", input);

        // ----------------------------------------------------------
        // Step 3: 算账 (Calculate) - 让 Java 算死数
        // ----------------------------------------------------------
        // 关键变更：传入 input (数据) + currentThreshold (规则)
        // 此时，ElectricityFormulaTool 就像一个只有逻辑没有数据的纯函数
        var calcResult = formulaTool.calculate(input, currentThreshold);
        log.info(">>>> [Worker] 计算完成, 结果: {}", calcResult.isExempt());

        List<String> evidenceFilenames = (ragResult.sources() == null)
                ? List.of()
                : ragResult.sources().stream()
                .map(PowerKnowledgeResult.SourceDocument::filename) // 只取文件名，扔掉下载链接
                .toList();

        // ----------------------------------------------------------
        // Step 4: 报告 (Report) - 组装证据链
        // ----------------------------------------------------------
        return new CalculationReport(
                calcResult.isExempt(),  // 对应 boolean isPassed
                calcResult.formulaUsed(),   // 对应 String conclusion (这里面包含了计算公式和金额)
                evidenceFilenames       // 对应 List<String> sources (如果不清洗，类型就不匹配)
        );
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
