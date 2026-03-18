package com.agenthub.api.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询改写服务 (Query Rewrite)
 * <p>
 * 使用 LLM 将用户口语化/简短的查询改写为更适合检索的表述
 * </p>
 *
 * <h3>改写策略：</h3>
 * <ul>
 *   <li>口语化 → 正式表达</li>
 *   <li>缩写/简称 → 全称</li>
 *   <li>同义词扩展</li>
 *   <li>补充缺失的上下文</li>
 * </ul>
 *
 * <h3>示例：</h3>
 * <pre>
 * 原查询: "电费怎么算"
 * 改写后: "电价计算方式 电费计算规则 电价构成"
 *
 * 原查询: "偏差考核严重吗"
 * 改写后: "偏差考核标准 偏差处罚规则 偏差考核规定"
 * </pre>
 */
@Slf4j
@Service
public class QueryRewriteService {

    private final ChatClient rewriteChatClient;
    private final ObjectMapper objectMapper;

    /**
     * 每次改写生成多少个相似查询
     */
    @Value("${query.rewrite.count:3}")
    private int rewriteCount;

    /**
     * 查询改写超时时间（毫秒）
     */
    @Value("${query.rewrite.timeout:10000}")
    private long timeout;

    /**
     * 是否启用查询改写
     */
    @Value("${query.rewrite.enabled:false}")
    private boolean enabled;

    /**
     * 系统提示词
     */
    private static final String SYSTEM_PROMPT = """
            你是一个查询改写专家，负责将用户的口语化查询改写为更适合知识库检索的正式表达。

            改写要求：
            1. 保持原查询的核心语义不变
            2. 补充可能的同义词、术语全称、行业用语
            3. 将口语化表达转换为正式书面语
            4. 如果原查询已经足够正式，直接返回原查询

            输出格式要求：
            - 返回 JSON 数组格式
            - 每个元素是一个改写后的查询字符串
            - 优先保留原查询，然后是改写后的查询
            - 最多返回 %d 个查询（包含原查询）

            示例：
            输入: "电费怎么算"
            输出: ["电费怎么算", "电价计算方式", "电费计算规则", "市场化交易电价"]

            输入: "偏差考核严重吗"
            输出: ["偏差考核严重吗", "偏差考核标准", "偏差考核处罚规则", "电力偏差考核"]
            """;

    public QueryRewriteService(
            @Qualifier("queryRewriteChatClient") ChatClient rewriteChatClient,
            ObjectMapper objectMapper) {
        this.rewriteChatClient = rewriteChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 改写查询
     *
     * @param originalQuery 原始查询
     * @return 改写后的查询列表（包含原查询）
     */
    public List<String> rewrite(String originalQuery) {
        if (!enabled) {
            log.debug("查询改写未启用，直接返回原查询");
            return List.of(originalQuery);
        }

        if (originalQuery == null || originalQuery.isBlank()) {
            return List.of(originalQuery);
        }

        try {
            log.debug("开始查询改写: {}", originalQuery);

            String prompt = String.format(SYSTEM_PROMPT, rewriteCount) + "\n\n原查询: " + originalQuery;

            String response = rewriteChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            List<String> rewrittenQueries = parseResponse(response, originalQuery);

            log.info("查询改写完成: 原查询={}, 改写后={}", originalQuery, rewrittenQueries.size());
            return rewrittenQueries;

        } catch (Exception e) {
            log.warn("查询改写失败，使用原查询: {}", e.getMessage());
            return List.of(originalQuery);
        }
    }

    /**
     * 解析 LLM 返回的 JSON 响应
     */
    private List<String> parseResponse(String response, String originalQuery) {
        List<String> queries = new ArrayList<>();

        try {
            // 尝试解析 JSON 数组
            JsonNode rootNode = objectMapper.readTree(response);

            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    String query = node.asText();
                    if (query != null && !query.isBlank()) {
                        queries.add(query);
                    }
                }
            }

            // 如果解析失败，尝试从文本中提取
            if (queries.isEmpty()) {
                queries = extractFromText(response);
            }

        } catch (JsonProcessingException e) {
            log.warn("JSON解析失败，尝试文本提取: {}", e.getMessage());
            queries = extractFromText(response);
        }

        // 确保原查询在结果中
        if (!queries.contains(originalQuery)) {
            queries.add(0, originalQuery);
        }

        // 限制返回数量
        return queries.subList(0, Math.min(queries.size(), rewriteCount));
    }

    /**
     * 从文本中提取查询（解析失败时的降级方案）
     */
    private List<String> extractFromText(String text) {
        List<String> queries = new ArrayList<>();

        // 按行分割，提取非空行
        String[] lines = text.split("[\n\r]+");
        for (String line : lines) {
            line = line.trim();
            // 移除常见的列表前缀符号
            line = line.replaceFirst("^[-*\\d.]+\\s*", "");
            line = line.replaceAll("^[\"']|[\"']$", "");

            if (!line.isBlank() && line.length() > 2) {
                queries.add(line);
            }
        }

        return queries;
    }

    /**
     * 获取是否启用查询改写
     */
    public boolean isEnabled() {
        return enabled;
    }
}
