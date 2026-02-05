package com.agenthub.api.agent_engine.service;

import com.agenthub.api.agent_engine.model.EvaluationResult;
import com.agenthub.api.prompt.service.ISysPromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自省服务 (Reflection Service)
 * <p>负责调用 Judge 模型对 Agent 的输出进行合规性和准确性审计</p>
 *
 * <h3>评判维度：</h3>
 * <ul>
 *   <li><b>幻觉检测 (最重要)</b>：是否编造了不存在的事实、数据或来源？</li>
 *   <li><b>来源标注</b>：是否明确说明信息来源（知识库/网上搜索/通用知识）</li>
 *   <li><b>内容准确性</b>：是否基于检索到的内容回答，而非臆测？</li>
 *   <li><b>问题相关性</b>：是否回答了用户的问题？</li>
 *   <li><b>回答完整性</b>：是否提供了完整的回答？</li>
 * </ul>
 *
 * <h3>幻觉判别标准：</h3>
 * <pre>
 * ❌ 幻觉行为：
 *   - 引用了不存在的文档、政策或法规
 *   - 编造了具体的数据（如价格、日期、数值）但检索结果中没有
 *   - 声称"根据知识库"或"根据搜索结果"但内容实际上是自己编的
 *   - 对检索结果进行了不合理的推断或夸大
 *
 * ✅ 正确行为：
 *   - 所有事实性陈述都能在检索结果中找到依据
 *   - 如果检索结果中没有答案，诚实说明而不是编造
 *   - 对不确定的信息使用"可能"、"约"等模糊词
 *   - 引用时准确，不夸大或曲解原始内容
 * </pre>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Slf4j
@Service
public class ReflectionService {

    private final ChatClient judgeClient;
    private final ISysPromptService sysPromptService;

    private static final String JUDGE_PROMPT_CODE = "SYSTEM-JUDGE-v1.0";
    private static final Pattern PASS_PATTERN = Pattern.compile("PASS", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAIL_PATTERN = Pattern.compile("FAIL::?(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);

    public ReflectionService(@Qualifier("judgeChatClient") ChatClient judgeClient,
                             ISysPromptService sysPromptService) {
        this.judgeClient = judgeClient;
        this.sysPromptService = sysPromptService;
    }

    /**
     * 执行审计
     *
     * @param query       用户问题
     * @param answer      Agent 的回答
     * @param ragContext  RAG 检索上下文 (通常是 List&lt;Document&gt; 或 Map)
     * @return 审计结果
     */
    public EvaluationResult evaluate(String query, String answer, Object ragContext) {
        log.info("[Judge] Starting evaluation for query: {}", query);

        try {
            // 1. 准备 Prompt 变量
            Map<String, Object> vars = new HashMap<>();
            vars.put("user_query", query);
            vars.put("agent_answer", answer);

            // 如果 ragContext 是 Map (包含 documents)，直接 putAll
            if (ragContext instanceof Map) {
                vars.putAll((Map<String, Object>) ragContext);
            } else {
                vars.put("documents", ragContext);
            }

            // 2. 渲染 System Prompt
            String systemPrompt = sysPromptService.render(JUDGE_PROMPT_CODE, vars);
            if (systemPrompt == null || systemPrompt.isEmpty()) {
                log.warn("[Judge] Prompt rendered empty, using fallback.");
                systemPrompt = buildFallbackPrompt(vars);
            }

            // 3. 调用 Judge 模型
            String result = judgeClient.prompt()
                    .system(systemPrompt)
                    .user("请开始审计，按以下格式输出：\nPASS（如果回答合格）\nFAIL: 原因（如果不合格，简要说明原因）")
                    .call()
                    .content();

            log.info("[Judge] Evaluation result: {}", result);

            // 4. 解析结果
            return parseResult(result);

        } catch (Exception e) {
            log.error("[Judge] Evaluation failed", e);
            // 降级策略：报错默认通过，避免阻塞用户
            return EvaluationResult.pass("Evaluation error: " + e.getMessage());
        }
    }

    /**
     * 构建备用提示词（当数据库中没有配置时）
     */
    private String buildFallbackPrompt(Map<String, Object> vars) {
        return "【重要时间上下文】当前时间是 2026 年 2 月。你不是 2024 年的模型，现在是 2026 年。\n\n"
            + "你是一个严格的内容质量审计专家。请仔细审计以下回答：\n\n"
            + "【用户问题】\n" + vars.get("user_query") + "\n\n"
            + "【Agent 回答】\n" + vars.get("agent_answer") + "\n\n"
            + "【审计标准】\n\n"
            + "== 1. 幻觉检测（最重要）==\n"
            + "❌ 以下情况判定为 FAIL：\n"
            + "   - 编造了检索结果中不存在的事实、数据、日期、价格、法规条款等\n"
            + "   - 声称\"根据知识库\"或\"根据搜索结果\"但内容与检索结果不符\n"
            + "   - 引用了具体的文档号、文件名、政策名称但在检索结果中找不到\n"
            + "   - 对检索结果进行了不合理的延伸或夸大\n"
            + "✅ 以下情况判定为 PASS：\n"
            + "   - 所有事实性陈述都能在检索结果中找到依据\n"
            + "   - 对不确定的信息使用了\"可能\"、\"约\"、\"预计\"等限定词\n"
            + "   - 检索结果中没有答案时，诚实说明\"抱歉，我暂时没有找到相关信息\"\n\n"
            + "== 2. 来源标注 ==\n"
            + "❌ 未说明信息来源\n"
            + "✅ 明确说明：\"根据广东电力市场知识库...\" / \"根据网上搜索结果...\" / \"根据通用常识...\"\n\n"
            + "== 3. 问题相关性 ==\n"
            + "❌ 回答与用户问题无关或偏离主题\n"
            + "✅ 直接回答了用户的问题\n\n"
            + "== 4. 回答完整性 ==\n"
            + "❌ 回答不完整或中途中断\n"
            + "✅ 提供了完整的回答\n\n"
            + "【特别说明】：现在是 2026 年，2026 年的数据属于当前/历史数据，不是未来信息。如果知识库中有 2026 年的数据，回答中引用这些数据是正确的。\n\n"
            + "【输出格式】\n"
            + "只输出以下两种之一：\n"
            + "PASS\n"
            + "FAIL: 具体原因（如：存在幻觉，编造了XX数据；未说明来源；回答不完整等）";
    }

    /**
     * 解析 LLM 输出的评估结果
     */
    private EvaluationResult parseResult(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return EvaluationResult.fail("Empty response from judge model");
        }

        // 检查是否包含 FAIL
        Matcher failMatcher = FAIL_PATTERN.matcher(llmOutput);
        if (failMatcher.find()) {
            String reason = failMatcher.group(1).trim();
            return EvaluationResult.fail(reason);
        }

        // 检查是否包含 PASS
        if (PASS_PATTERN.matcher(llmOutput).find()) {
            return EvaluationResult.pass(null);
        }

        // 默认处理（如果没按格式输出）
        log.warn("[Judge] Unexpected output format: {}", llmOutput);
        return EvaluationResult.pass("Judge output format unclear, treating as pass");
    }
}
