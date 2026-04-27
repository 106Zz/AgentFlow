package com.agenthub.api.agent_engine.service;

import com.agenthub.api.agent_engine.model.IntentResult;
import com.agenthub.api.agent_engine.model.IntentType;
import com.agenthub.api.mq.domain.LLMRetryMessage;
import com.agenthub.api.mq.producer.LLMRetryProducer;
import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.agenthub.api.prompt.service.ISysPromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图识别服务
 * <p>区分知识库查询 (KB_QA) 和通用对话 (CHAT)</p>
 *
 * <h3>识别策略：</h3>
 * <ol>
 *   <li>LLM 分类：qwen-plus 意图识别 (准确，覆盖复杂语义)</li>
 *   <li>规则兜底：LLM 失败时使用关键词匹配 (快速降级)</li>
 *   <li>降级处理：均失败时默认走 CHAT 流程</li>
 * </ol>
 *
 * @author AgentHub
 * @since 2026-02-07
 */
@Slf4j
@Service
public class IntentRecognitionService {

    private final ChatClient intentChatClient;
    private final ISysPromptService sysPromptService;
    private final LLMRetryProducer llmRetryProducer;

    /**
     * 提示词代码
     */
    private static final String PROMPT_CODE = "ROUTER-INTENT-v1.0";

    /**
     * 知识库检索高置信度关键词 (仅用于 LLM 失败时的兜底规则匹配)
     * <p>原则：只保留能明确指向「需要查阅具体文档证据」的词，排除语义模糊的通用词</p>
     */
    private static final List<String> KB_STRONG_KEYWORDS = List.of(
            // 电力市场专业术语（强指向性）
            "电价", "电费", "结算", "考核", "偏差", "市场化", "交易",
            "售电", "购电", "输配电", "政府定价", "输电费",
            "容量电费", "需量电费", "功率因数", "力调", "力率",
            "峰谷平", "分时电价", "尖峰", "低谷", "峰谷",
            "中长期", "现货", "批发", "零售", "辅助服务",
            "调峰", "调频", "备用", "无功", "补偿",
            // 明确的文档查阅意图
            "知识库", "检索", "文档", "规定", "标准", "规则", "办法"
    );

    /**
     * 规则兜底置信度（保守，低于 LLM 的判断可信度）
     */
    private static final double KEYWORD_FALLBACK_CONFIDENCE = 0.6;

    /**
     * 闲聊模式正则
     */
    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^(你好|嗨|hi|hello|嗨嗨|哈喽|谢谢|感谢|再见|拜拜|在吗|在不在|你是谁|叫什么|名字).*$",
            Pattern.CASE_INSENSITIVE
    );

    public IntentRecognitionService(
            @Qualifier("intentChatClient") ChatClient intentChatClient,
            ISysPromptService sysPromptService,
            LLMRetryProducer llmRetryProducer) {
        this.intentChatClient = intentChatClient;
        this.sysPromptService = sysPromptService;
        this.llmRetryProducer = llmRetryProducer;
    }

    /**
     * 识别用户意图
     *
     * @param query 用户查询
     * @return 意图识别结果
     */
    public IntentResult recognizeIntent(String query) {
        if (query == null || query.isBlank()) {
            log.debug("[Intent] 查询为空，返回 UNKNOWN");
            return IntentResult.unknown("查询为空");
        }

        log.debug("[Intent] 开始识别: {}", query);

        // 1. 快速闲聊判断（无需调 LLM，节省开销）
        if (isChattyQuery(query)) {
            log.info("[Intent] 闲聊快速匹配: CHAT");
            return IntentResult.chat(0.9, "明显的闲聊/问候模式");
        }

        // 2. LLM 意图识别（主力，优先走 qwen-plus）
        try {
            IntentResult llmResult = recognizeByLLM(query);
            log.info("[Intent] LLM识别: intent={}, confidence={}, reasoning={}",
                    llmResult.intent(), llmResult.confidence(), llmResult.reasoning());
            return llmResult;
        } catch (Exception e) {
            log.warn("[Intent] LLM识别失败，使用规则兜底: {}", e.getMessage());
        }

        // 3. 规则兜底（LLM 失败时才走）
        IntentResult ruleResult = checkByRules(query);
        if (ruleResult != null) {
            log.info("[Intent] 规则兜底: intent={}, confidence={}, reasoning={}",
                    ruleResult.intent(), ruleResult.confidence(), ruleResult.reasoning());
            return ruleResult;
        }

        // 4. 均无法判断，默认 CHAT
        return IntentResult.chat(0.5, "LLM 与规则均未命中，默认 CHAT");
    }

    /**
     * 基于规则的兜底判断（仅 LLM 失败时调用）
     *
     * @param query 用户查询
     * @return 意图识别结果，如果无法判断返回 null
     */
    private IntentResult checkByRules(String query) {
        String lowerQuery = query.toLowerCase();

        long matchCount = KB_STRONG_KEYWORDS.stream()
                .filter(keyword -> lowerQuery.contains(keyword))
                .count();

        if (matchCount >= 2) {
            String matchedKeyword = KB_STRONG_KEYWORDS.stream()
                    .filter(lowerQuery::contains)
                    .findFirst()
                    .orElse("");
            return IntentResult.kbQa(
                    KEYWORD_FALLBACK_CONFIDENCE,
                    "[规则兜底] 包含 " + matchCount + " 个知识库关键词: " + matchedKeyword + " 等"
            );
        }

        if (matchCount == 1) {
            String matchedKeyword = KB_STRONG_KEYWORDS.stream()
                    .filter(lowerQuery::contains)
                    .findFirst()
                    .orElse("");
            return IntentResult.kbQa(
                    0.5,
                    "[规则兜底] 仅包含 1 个知识库关键词: " + matchedKeyword
            );
        }

        return null;
    }

    /**
     * 判断是否为闲聊查询
     */
    private boolean isChattyQuery(String query) {
        return CHAT_PATTERN.matcher(query.trim()).matches();
    }

    /**
     * 基于 LLM 的意图识别
     * <p>核心判断：是否需要「基于已有文档证据」来回答</p>
     *
     * @param query 用户查询
     * @return 意图识别结果
     */
    private IntentResult recognizeByLLM(String query) {
        // 从 sys_prompt 表获取提示词
        SysPrompt sysPrompt = sysPromptService.getByCode(PROMPT_CODE);
        if (sysPrompt == null || sysPrompt.getContent() == null) {
            log.warn("[Intent] 未找到提示词: {}, 使用降级方案", PROMPT_CODE);
            return IntentResult.chat(0.5, "提示词未配置，默认 CHAT");
        }

        // 从 JSON 中提取 template 字段
        String systemPromptText;
        try {
            com.fasterxml.jackson.databind.JsonNode contentNode = sysPrompt.getContent();
            if (contentNode.has("template")) {
                systemPromptText = contentNode.get("template").asText();
            } else {
                // 降级：如果没有 template 字段，使用整个 content
                systemPromptText = contentNode.asText();
            }
        } catch (Exception e) {
            log.warn("[Intent] 解析提示词内容失败: {}, 使用降级方案", e.getMessage());
            return IntentResult.chat(0.5, "提示词解析失败，默认 CHAT");
        }

        if (systemPromptText == null || systemPromptText.isEmpty()) {
            log.warn("[Intent] 提示词内容为空，使用降级方案");
            return IntentResult.chat(0.5, "提示词内容为空，默认 CHAT");
        }

        log.debug("[Intent] 使用提示词: {}", systemPromptText.substring(0, Math.min(100, systemPromptText.length())));

        try {
            String response = intentChatClient.prompt()
                    .system(systemPromptText)
                    .user(query)
                    .call()
                    .content();

            log.debug("[Intent] LLM返回: {}", response);

            return parseIntentResult(response);
        } catch (Exception e) {
            // 检查是否为限流错误
            if (isRateLimitError(e)) {
                log.warn("[Intent] 检测到限流错误，发送重试消息到 MQ: {}", e.getMessage());
                // 发送重试消息到 MQ（30 秒后重试）
                LLMRetryMessage retryMessage = LLMRetryMessage.forIntentRecognition(null, null, query);
                llmRetryProducer.sendRetryMessage(retryMessage, 30_000L);
                // 返回降级结果
                return IntentResult.chat(0.3, "请求限流，已加入重试队列");
            }
            // 其他错误直接抛出，由上层处理
            throw e;
        }
    }

    /**
     * 检查是否为限流错误
     *
     * @param e 异常
     * @return 是否为限流错误
     */
    private boolean isRateLimitError(Exception e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 检查常见的限流错误标识
        return message.contains("rate limit") ||
                message.contains("Rate limit") ||
                message.contains("429") ||
                message.contains("Too Many Requests") ||
                message.contains("请求过于频繁") ||
                message.contains("限流");
    }

    /**
     * 解析 LLM 返回的 JSON 结果
     *
     * @param json LLM 返回的 JSON 字符串
     * @return 意图识别结果
     */
    private IntentResult parseIntentResult(String json) {
        try {
            // 提取 intent 字段
            String intentStr = extractJsonField(json, "intent");
            double confidence = Double.parseDouble(
                    extractJsonField(json, "confidence")
            );
            String reasoning = extractJsonField(json, "reasoning");

            IntentType intent;
            if ("KB_QA".equalsIgnoreCase(intentStr)) {
                intent = IntentType.KB_QA;
            } else if ("CHAT".equalsIgnoreCase(intentStr)) {
                intent = IntentType.CHAT;
            } else {
                log.warn("[Intent] 未知的意图类型: {}, 默认 CHAT", intentStr);
                intent = IntentType.CHAT;
            }

            // 修复：预检索条件放宽，只要识别为 KB_QA 就预检索
            boolean needsPreRetrieval = intent == IntentType.KB_QA;

            return new IntentResult(intent, confidence, reasoning, needsPreRetrieval);

        } catch (Exception e) {
            log.warn("[Intent] 解析失败: {}, 错误: {}", json, e.getMessage());
            return IntentResult.chat(0.5, "解析失败，默认 CHAT");
        }
    }

    /**
     * 从 JSON 字符串中提取字段值
     *
     * @param json  JSON 字符串
     * @param field 字段名
     * @return 字段值
     */
    private String extractJsonField(String json, String field) {
        // 匹配 "field": "value" 或 "field": value 格式
        String pattern = "\"" + field + "\"\\s*:\\s*\"?([^,\"}\\}]+)\"?";
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1).trim();
        }

        // 尝试匹配 value 在行尾的情况 (如 "intent": KB_QA)
        pattern = "\"" + field + "\"\\s*:\\s*([a-zA-Z_0-9\\.]+)";
        p = Pattern.compile(pattern);
        m = p.matcher(json);
        if (m.find()) {
            return m.group(1).trim();
        }

        return "";
    }
}
