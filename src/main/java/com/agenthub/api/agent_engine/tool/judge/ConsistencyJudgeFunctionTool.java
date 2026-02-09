package com.agenthub.api.agent_engine.tool.judge;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

/**
 * 内容一致性审计工具 (Spring AI @Tool 注解方式)
 * <p>用于 Judge LLM 调用的工具，基于提供的证据判断 Agent 回答的准确性</p>
 *
 * <h3>使用场景：</h3>
 * <ul>
 *   <li>ReflectionService 调用 Judge LLM 时通过 .tools() 注册此工具</li>
 *   <li>Judge LLM 分析后可调用此工具以结构化方式返回评估结果</li>
 * </ul>
 *
 * <h3>判断规则：</h3>
 * <ol>
 *   <li>幻觉检测：回答中包含具体事实，但证据中找不到 → FAIL</li>
 *   <li>虚假引用检测：声称"根据资料/文档/检索结果"，但证据为空或找不到依据 → FAIL</li>
 *   <li>证据为空 + 明确说明"未检索到" → PASS</li>
 * </ol>
 *
 * @author AgentHub
 * @since 2026-02-10
 */
@Component("consistencyJudgeFunctionTool")
@Description("内容一致性审计工具组件")
public class ConsistencyJudgeFunctionTool {

    private static final String[] REFERENCE_KEYWORDS = {
            "根据资料", "根据文档", "根据检索结果", "根据搜索结果",
            "资料显示", "文档中说", "知识库显示", "检索结果显示"
    };

    private static final String[] EMPTY_EVIDENCE_PASS_KEYWORDS = {
            "未检索到", "没有找到", "抱歉", "暂时没有", "无法提供"
    };

    /**
     * 评估一致性
     *
     * @param userQuery   用户问题
     * @param agentAnswer Agent 的回答
     * @param evidence    证据内容（JSON 数组格式）
     * @return PASS 或 FAIL 及具体原因
     */
    @Tool(description = "内容一致性审计工具。基于提供的证据，判断 Agent 回答是否存在编造事实、虚假引用等问题。返回 PASS 或 FAIL 及具体原因。")
    public String evaluateConsistency(
            @ToolParam(description = "用户问题") String userQuery,
            @ToolParam(description = "Agent的回答") String agentAnswer,
            @ToolParam(description = "证据内容（JSON数组格式或纯文本）") String evidence) {

        // 解析证据
        boolean hasEvidence = evidence != null && !evidence.trim().isEmpty()
                           && !evidence.equals("[]") && !evidence.equals("{}");

        String lowerAnswer = agentAnswer != null ? agentAnswer.toLowerCase() : "";

        // 规则 1: 证据为空的情况
        if (!hasEvidence) {
            // 如果明确说明未检索到 → PASS
            for (String keyword : EMPTY_EVIDENCE_PASS_KEYWORDS) {
                if (lowerAnswer.contains(keyword)) {
                    return "PASS: 证据为空，但回答明确说明未检索到相关信息";
                }
            }

            // 如果声称根据资料/文档 → FAIL
            for (String keyword : REFERENCE_KEYWORDS) {
                if (lowerAnswer.contains(keyword.toLowerCase())) {
                    return "FAIL: 声称根据资料/文档/检索结果，但证据为空";
                }
            }

            // 如果包含具体事实 → FAIL
            if (containsFactualClaims(agentAnswer)) {
                return "FAIL: 回答包含具体事实，但证据为空";
            }

            return "PASS";
        }

        // 规则 2: 有证据，检查是否虚假引用
        boolean hasReferenceClaim = false;
        for (String keyword : REFERENCE_KEYWORDS) {
            if (lowerAnswer.contains(keyword.toLowerCase())) {
                hasReferenceClaim = true;
                break;
            }
        }

        // 如果声称根据资料但证据过短/无意义 → FAIL
        if (hasReferenceClaim && evidence.length() < 20) {
            return "FAIL: 声称根据资料/文档，但证据内容过少";
        }

        // 如果包含具体事实且证据过短 → FAIL
        if (containsFactualClaims(agentAnswer) && evidence.length() < 20) {
            return "FAIL: 回答包含具体事实，但证据内容过少";
        }

        return "PASS";
    }

    /**
     * 检测回答中是否包含具体事实性陈述
     * <p>判断依据：包含数字 + (日期 或 小数 或 单位)</p>
     */
    private boolean containsFactualClaims(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        boolean hasNumber = text.matches(".*\\d+.*");
        boolean hasDate = text.matches(".*\\d{4}年.*") || text.matches(".*\\d{1,2}月.*");
        boolean hasDecimal = text.matches(".*\\d+\\.\\d+.*");
        boolean hasPriceUnit = text.contains("元") || text.contains("%");

        return hasNumber && (hasDate || hasDecimal || hasPriceUnit);
    }
}
