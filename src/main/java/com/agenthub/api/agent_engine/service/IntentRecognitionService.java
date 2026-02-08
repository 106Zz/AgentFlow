package com.agenthub.api.agent_engine.service;

import com.agenthub.api.agent_engine.model.IntentResult;
import com.agenthub.api.agent_engine.model.IntentType;
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
 *   <li>规则匹配：关键词匹配 (快速，覆盖常见场景)</li>
 *   <li>LLM 分类：规则不确定时调用 LLM (准确，覆盖长尾场景)</li>
 *   <li>降级处理：识别失败时默认走 CHAT 流程</li>
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

    /**
     * 提示词代码
     */
    private static final String PROMPT_CODE = "ROUTER-INTENT-v1.0";

    /**
     * 广东电力市场相关关键词 + 明确的检索意图关键词 (用于规则匹配)
     */
    private static final List<String> KB_KEYWORDS = List.of(
            // 电力市场专业术语
            "电价", "电费", "结算", "考核", "偏差", "市场化", "交易",
            "售电", "购电", "输配电", "政府定价", "输电费",
            "容量电费", "需量电费", "功率因数", "力调", "力率",
            "峰谷平", "分时电价", "尖峰", "低谷", "峰谷",
            "中长期", "现货", "批发", "零售", "辅助服务",
            "调峰", "调频", "备用", "无功", "补偿",
            // 明确的检索意图关键词 (高优先级)
            "知识库", "检索", "查询", "搜索", "查找", "文档",
            "政策", "文件", "规定", "标准", "规则", "办法",
            "目标", "规划", "发展目标"
    );

    /**
     * 关键词匹配高置信度阈值 (2个以上关键词)
     */
    private static final double KEYWORD_HIGH_CONFIDENCE = 0.85;

    /**
     * 单个关键词中等置信度阈值
     */
    private static final double KEYWORD_MEDIUM_CONFIDENCE = 0.65;

    /**
     * 闲聊模式正则
     */
    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^(你好|嗨|hi|hello|嗨嗨|哈喽|谢谢|感谢|再见|拜拜|在吗|在不在|你是谁|叫什么|名字).*$",
            Pattern.CASE_INSENSITIVE
    );

    public IntentRecognitionService(
            @Qualifier("intentChatClient") ChatClient intentChatClient,
            ISysPromptService sysPromptService) {
        this.intentChatClient = intentChatClient;
        this.sysPromptService = sysPromptService;
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

        // 1. 快速规则判断 (关键词匹配)
        IntentResult ruleResult = checkByRules(query);
        if (ruleResult != null && ruleResult.isHighConfidence()) {
            log.info("[Intent] 规则匹配高置信度: intent={}, confidence={}, reasoning={}",
                    ruleResult.intent(), ruleResult.confidence(), ruleResult.reasoning());
            return ruleResult;
        }

        // 2. 中等置信度规则匹配，记录但不直接返回
        //    如果 LLM 失败，可以降级使用这个结果
        IntentResult mediumResult = ruleResult;

        // 3. LLM 意图识别
        try {
            IntentResult llmResult = recognizeByLLM(query);
            log.info("[Intent] LLM识别: intent={}, confidence={}, reasoning={}",
                    llmResult.intent(), llmResult.confidence(), llmResult.reasoning());
            return llmResult;
        } catch (Exception e) {
            log.warn("[Intent] LLM识别失败，降级为规则结果或 CHAT: {}", e.getMessage());
            if (mediumResult != null) {
                return mediumResult;
            }
            return IntentResult.chat(0.5, "LLM识别失败，降级为 CHAT");
        }
    }

    /**
     * 基于规则的快速判断
     *
     * @param query 用户查询
     * @return 意图识别结果，如果无法判断返回 null
     */
    private IntentResult checkByRules(String query) {
        String lowerQuery = query.toLowerCase();

        // 1. 检查闲聊模式 → 高置信度 CHAT
        if (CHAT_PATTERN.matcher(query.trim()).matches()) {
            return IntentResult.chat(0.9, "明显的闲聊/问候模式");
        }

        // 2. 精确关键词匹配
        long matchCount = KB_KEYWORDS.stream()
                .filter(keyword -> lowerQuery.contains(keyword))
                .count();

        if (matchCount >= 2) {
            return IntentResult.kbQa(
                    KEYWORD_HIGH_CONFIDENCE,
                    "包含 " + matchCount + " 个知识库关键词: " +
                            KB_KEYWORDS.stream()
                                    .filter(lowerQuery::contains)
                                    .findFirst()
                                    .orElse("") + " 等"
            );
        }

        if (matchCount == 1) {
            String matchedKeyword = KB_KEYWORDS.stream()
                    .filter(lowerQuery::contains)
                    .findFirst()
                    .orElse("");
            return IntentResult.kbQa(
                    KEYWORD_MEDIUM_CONFIDENCE,
                    "包含 1 个知识库关键词: " + matchedKeyword + "，置信度中等"
            );
        }

        // 3. 未匹配到关键词
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

        String response = intentChatClient.prompt()
                .system(systemPromptText)
                .user(query)
                .call()
                .content();

        log.debug("[Intent] LLM返回: {}", response);

        return parseIntentResult(response);
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
