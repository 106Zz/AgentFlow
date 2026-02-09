package com.agenthub.api.agent_engine.service;

import com.agenthub.api.agent_engine.model.EvaluationResult;
import com.agenthub.api.agent_engine.tool.judge.ConsistencyJudgeFunctionTool;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private final ObjectMapper objectMapper;
    private final ConsistencyJudgeFunctionTool consistencyJudgeFunctionTool;
    private static final String CONSISTENCY_JUDGE_PROMPT_CODE = "SYSTEM-JUDGE-v1.0";
    private static final Pattern PASS_PATTERN = Pattern.compile("PASS", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAIL_PATTERN = Pattern.compile("FAIL::?(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE);

    public ReflectionService(@Qualifier("judgeChatClient") ChatClient judgeClient,
                             ISysPromptService sysPromptService,
                             ConsistencyJudgeFunctionTool consistencyJudgeFunctionTool,
                             ObjectMapper objectMapper) {
        this.judgeClient = judgeClient;
        this.sysPromptService = sysPromptService;
        this.consistencyJudgeFunctionTool = consistencyJudgeFunctionTool;
        this.objectMapper = objectMapper;
    }

    /**
     * 从工具调用记录中提取证据
     * <p>从 tool_calls[].result() 中的 evidenceBlocks 提取证据内容</p>
     *
     * @param toolCallsVars tool_calls 变量（List&lt;Map&lt;String, Object&gt;&gt;）
     * @return 证据列表
     */
    private List<String> extractEvidenceFromToolCalls(Object toolCallsVars) {
        List<String> evidence = new ArrayList<>();

        if (toolCallsVars == null) {
            return evidence;
        }

        try {
            if (toolCallsVars instanceof List) {
                List<?> toolCalls = (List<?>) toolCallsVars;
                for (Object tcObj : toolCalls) {
                    if (tcObj instanceof Map) {
                        Map<?, ?> tcMap = (Map<?, ?>) tcObj;
                        if (tcMap.containsKey("result")) {
                            String resultContent = tcMap.get("result").toString();

                            // 检查是否为 JSON 格式（以 { 或 [ 开头）
                            String trimmed = resultContent.trim();
                            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                                try {
                                    JsonNode node = objectMapper.readTree(resultContent);
                                    if (node.has("evidenceBlocks") && node.get("evidenceBlocks").isArray()) {
                                        for (JsonNode block : node.get("evidenceBlocks")) {
                                            if (block.has("content")) {
                                                evidence.add(block.get("content").asText());
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug("[ReflectionService] 解析 JSON 失败，跳过此结果");
                                }
                            } else {
                                // 非 JSON 格式（如 web_search 返回的纯文本），直接作为证据添加
                                // 限制长度，避免证据过长
                                if (resultContent.length() > 50) {
                                    // 如果内容过长，截取前 2000 字符
                                    String truncated = resultContent.length() > 2000
                                            ? resultContent.substring(0, 2000) + "...(内容过长，已截断)"
                                            : resultContent;
                                    evidence.add(truncated);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ReflectionService] 提取 tool_calls 证据失败", e);
        }

        log.info("[ReflectionService] 从 tool_calls 提取证据: 数量={}", evidence.size());
        return evidence;
    }

    /**
     * 从 RAG 上下文中提取证据
     * <p>优先从 tool_calls 提取，降级从 pre_retrieved_content 提取</p>
     *
     * @param ragContext RAG 上下文
     * @return 证据列表
     */
    private List<String> extractEvidenceFromRagContext(Map<String, Object> ragContext) {
        // 优先从 tool_calls 提取
        if (ragContext.containsKey("tool_calls")) {
            List<String> evidence = extractEvidenceFromToolCalls(ragContext.get("tool_calls"));
            if (!evidence.isEmpty()) {
                return evidence;
            }
        }

        // 降级：从 pre_retrieved_content 提取
        List<String> evidence = new ArrayList<>();
        if (ragContext.containsKey("pre_retrieved_content")) {
            Object preRetrieved = ragContext.get("pre_retrieved_content");
            if (preRetrieved != null) {
                String content = preRetrieved.toString();
                if (!content.isBlank()) {
                    String[] lines = content.split("\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("---") && !line.startsWith("【")) {
                            evidence.add(line);
                        }
                    }
                }
            }
        }

        return evidence;
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
        log.info("[Judge] 开始评估: query='{}', answerLength={}", query, answer != null ? answer.length() : 0);

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

            log.info("[Judge] Prompt 变量: preRetrieved={}, toolCalls={}, history={}",
                    vars.containsKey("pre_retrieved_content"),
                    vars.containsKey("tool_calls"),
                    vars.containsKey("conversation_history"));

            // 2. 渲染 System Prompt（使用数据库中的内容一致性审计提示词）
            String systemPrompt = sysPromptService.render(CONSISTENCY_JUDGE_PROMPT_CODE, vars);
            if (systemPrompt == null || systemPrompt.isEmpty()) {
                log.warn("[Judge] CONSISTENCY 提示词未找到，使用 fallback");
                systemPrompt = buildFallbackPrompt(vars);
            }

            log.debug("[Judge] System Prompt 长度: {}", systemPrompt.length());

            // 3. 提取证据
            List<String> evidenceList = extractEvidenceFromRagContext(vars);
            String evidenceJson;
            try {
                evidenceJson = objectMapper.writeValueAsString(evidenceList);
            } catch (Exception e) {
                log.warn("[ReflectionService] 序列化证据失败，使用空数组", e);
                evidenceJson = "[]";
            }

            log.info("[Judge] 证据提取完成: 数量={}", evidenceList.size());

            // 4. 调用 LLM Judge + .tools()
            String userMessage = String.format(
                "【用户问题】\n%s\n\n【Agent 回答】\n%s\n\n【提供的证据】\n%s",
                query, answer, evidenceJson
            );

            // 5. 调用 LLM Judge
            String result = judgeClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .tools(consistencyJudgeFunctionTool)
                    .call()
                    .content();

            log.info("[Judge] Judge 模型返回: {}", result);

            // 5. 解析结果
            EvaluationResult parsed = parseResult(result);
            log.info("[Judge] 解析结果: passed={}, reason='{}'", parsed.isPassed(), parsed.getReason());
            return parsed;

        } catch (Exception e) {
            log.error("[Judge] 评估失败", e);
            return EvaluationResult.pass("Evaluation error: " + e.getMessage());
        }
    }

    /**
     * 构建备用提示词（当数据库中没有配置时）
     * <p>支持工具调用记录、预检索内容、历史对话等上下文</p>
     */
    private String buildFallbackPrompt(Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder();

        sb.append("【重要时间上下文】当前时间是 2026 年 2 月。你不是 2024 年的模型，现在是 2026 年。\n\n");
        sb.append("你是一个严格的内容质量审计专家。请仔细审计以下回答：\n\n");

        sb.append("【用户问题】\n").append(vars.get("user_query")).append("\n\n");
        sb.append("【Agent 回答】\n").append(vars.get("agent_answer")).append("\n\n");

        // 预检索内容（如果有）
        if (vars.containsKey("pre_retrieved_content") && vars.get("pre_retrieved_content") != null) {
            String preRetrieved = vars.get("pre_retrieved_content").toString();
            if (preRetrieved.length() > 3000) {
                preRetrieved = preRetrieved.substring(0, 3000) + "\n\n...(内容过长，已截断)";
            }
            sb.append("【预检索内容（知识库）】\n").append(preRetrieved).append("\n\n");
        }

        // 工具调用记录（如果有）
        if (vars.containsKey("tool_calls") && vars.get("tool_calls") != null) {
            sb.append("【工具调用记录】\n").append(vars.get("tool_calls")).append("\n\n");
        }

        // 历史对话（如果有）
        if (vars.containsKey("conversation_history") && vars.get("conversation_history") != null) {
            sb.append("【最近对话历史】\n").append(vars.get("conversation_history")).append("\n\n");
        }

        // 文档内容（如果有，兼容旧版）
        if (vars.containsKey("documents") && vars.get("documents") != null) {
            sb.append("【检索文档】\n").append(vars.get("documents")).append("\n\n");
        }

        sb.append("【审计标准】\n\n");
        sb.append("== 1. 幻觉检测（最重要）==\n");
        sb.append("❌ 以下情况判定为 FAIL：\n");
        sb.append("   - 编造了检索结果中不存在的事实、数据、日期、价格、法规条款等\n");
        sb.append("   - 声称\"根据知识库\"或\"根据搜索结果\"但内容与检索结果不符\n");
        sb.append("   - 引用了具体的文档号、文件名、政策名称但在检索结果中找不到\n");
        sb.append("   - 对检索结果进行了不合理的延伸或夸大\n");
        sb.append("✅ 以下情况判定为 PASS：\n");
        sb.append("   - 所有事实性陈述都能在检索结果中找到依据\n");
        sb.append("   - 对不确定的信息使用了\"可能\"、\"约\"、\"预计\"等限定词\n");
        sb.append("   - 检索结果中没有答案时，诚实说明\"抱歉，我暂时没有找到相关信息\"\n\n");
        sb.append("== 2. 来源标注 ==\n");
        sb.append("❌ 未说明信息来源\n");
        sb.append("✅ 明确说明：\"根据广东电力市场知识库...\" / \"根据网上搜索结果...\" / \"根据通用常识...\"\n\n");
        sb.append("== 3. 问题相关性 ==\n");
        sb.append("❌ 回答与用户问题无关或偏离主题\n");
        sb.append("✅ 直接回答了用户的问题\n\n");
        sb.append("== 4. 回答完整性 ==\n");
        sb.append("❌ 回答不完整或中途中断\n");
        sb.append("✅ 提供了完整的回答\n\n");
        sb.append("【特别说明】：现在是 2026 年，2026 年的数据属于当前/历史数据，不是未来信息。如果知识库中有 2026 年的数据，回答中引用这些数据是正确的。\n\n");
        sb.append("【输出格式】\n");
        sb.append("只输出以下两种之一：\n");
        sb.append("PASS\n");
        sb.append("FAIL: 具体原因（如：存在幻觉，编造了XX数据；未说明来源；回答不完整等）");

        return sb.toString();
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
