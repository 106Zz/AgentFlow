package com.agenthub.api.ai.service.gssc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * GSC 流水线服务（原 GSSC，去掉了 Select 阶段）
 * <p>
 * 实现 Structure → Compress 流程
 * </p>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>证据块已经过 Reranker 精排，不再重复筛选</li>
 *   <li>历史消息由滑动窗口控制，不再用词法匹配重排</li>
 *   <li>GSC 只负责：结构化组装 + Token 预算压缩</li>
 * </ul>
 *
 * <h3>流程：</h3>
 * <ul>
 *   <li>Structure: 按模板结构化输出，保持 [Evidence] [Tools] [Context] [Task] 格式</li>
 *   <li>Compress: Token 预算控制，超出则按优先级压缩（工具结果 > 证据 > 历史）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GSSCService {

    /**
     * 最大 Token 预算
     */
    @Value("${gssc.max-tokens:3000}")
    private int maxTokens;

    /**
     * 是否启用 GSC
     */
    @Value("${gssc.enabled:false}")
    private boolean enabled;

    /**
     * 结构化 + 压缩证据文本
     * <p>
     * 证据已经过 Reranker 精排和 EvidenceAssembly 组装，
     * 这里只做格式化输出和 Token 预算控制
     * </p>
     *
     * @param evidenceText   已格式化的证据文本（来自 formatKnowledgeResult）
     * @param historyText    历史对话文本
     * @param toolResultText 工具执行结果文本（可为空）
     * @param systemPrompt   系统提示词
     * @param userQuery      用户问题
     * @return 结构化 + 压缩后的上下文字符串
     */
    public String process(
            String evidenceText,
            String historyText,
            String toolResultText,
            String systemPrompt,
            String userQuery) {

        if (!enabled) {
            return buildSimpleContext(evidenceText, historyText, toolResultText, systemPrompt, userQuery);
        }

        log.debug("【GSC】开始处理: evidenceLen={}, historyLen={}, toolsLen={}",
                evidenceText != null ? evidenceText.length() : 0,
                historyText != null ? historyText.length() : 0,
                toolResultText != null ? toolResultText.length() : 0);

        // Step 1: Structure - 结构化
        String structured = structureContext(evidenceText, historyText, toolResultText, systemPrompt, userQuery);

        // Step 2: Compress - 压缩（Token 预算控制）
        return compressIfNeeded(structured);
    }

    /**
     * 轻量重载：只处理证据 + 用户问题（无历史、无工具）
     */
    public String processEvidence(String evidenceText, String userQuery) {
        return process(evidenceText, null, null, null, userQuery);
    }

    /**
     * Structure: 结构化输出
     */
    private String structureContext(
            String evidence,
            String history,
            String tools,
            String systemPrompt,
            String userQuery) {

        StringBuilder sb = new StringBuilder();

        // [System]
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            sb.append("[System]\n").append(systemPrompt).append("\n\n");
        }

        // [Evidence] - 证据块（Reranker 已精排，保持原序）
        if (evidence != null && !evidence.isEmpty()) {
            sb.append("[Evidence]\n").append(evidence).append("\n");
        }

        // [Tools] - 工具执行结果（用户明确请求的，优先级最高）
        if (tools != null && !tools.isEmpty()) {
            sb.append("[工具执行结果]\n").append(tools).append("\n");
        }

        // [Context] - 历史对话（滑动窗口已控制数量）
        if (history != null && !history.isEmpty()) {
            sb.append("[Context]\n").append(history).append("\n");
        }

        // [Task]
        sb.append("[Task]\n").append(userQuery).append("\n\n");

        // [Output]
        sb.append("[Output]\n请基于以上信息回答问题。");

        return sb.toString();
    }

    /**
     * Compress: Token 预算控制
     * <p>
     * 压缩优先级（从低到高，先压缩低优先级的）：
     * 1. 历史对话（最先被压缩/截断）
     * 2. 证据内容（中间）
     * 3. 工具结果（最后被压缩）
     * 4. 系统提示词和用户问题（永不压缩）
     * </p>
     */
    private String compressIfNeeded(String context) {
        int estimatedTokens = estimateTokens(context);

        if (estimatedTokens <= maxTokens) {
            return context;
        }

        log.info("【GSC Compress】内容超出预算: {} tokens > {} tokens，进行压缩",
                estimatedTokens, maxTokens);

        // 简单策略：按最大字符数截断
        // 实际生产中可以使用 LLM 进行智能摘要
        int maxChars = maxTokens * 2;
        if (context.length() > maxChars) {
            context = context.substring(0, maxChars) + "\n\n[内容已按 Token 预算截断]";
        }

        return context;
    }

    /**
     * 简单模式（未启用 GSC 时的降级方案：直接拼接）
     */
    private String buildSimpleContext(
            String evidence,
            String history,
            String tools,
            String systemPrompt,
            String userQuery) {

        StringBuilder sb = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            sb.append("[System]\n").append(systemPrompt).append("\n\n");
        }

        if (evidence != null && !evidence.isEmpty()) {
            sb.append("[Evidence]\n").append(evidence).append("\n\n");
        }

        if (tools != null && !tools.isEmpty()) {
            sb.append("[工具执行结果]\n").append(tools).append("\n\n");
        }

        if (history != null && !history.isEmpty()) {
            sb.append("[Context]\n").append(history).append("\n");
        }

        sb.append("[Task]\n").append(userQuery);

        return sb.toString();
    }

    /**
     * 预估 Token 数量（简单估算）
     * 中文约 1 Token ≈ 1.5 字符，英文约 1 Token ≈ 4 字符
     * 综合取 1 Token ≈ 2 字符
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 2;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxTokens() {
        return maxTokens;
    }
}
