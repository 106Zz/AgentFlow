package com.agenthub.api.agent_engine.tool.impl;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Web 搜索工具
 * <p>使用 Tavily Search API 进行互联网搜索，专为 AI 优化</p>
 * <p>API 文档: https://docs.tavily.com/docs/tavily-search/rest</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool implements AgentTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${agent.tavily.endpoint:https://api.tavily.com/search}")
    private String tavilyEndpoint;

    @Value("${agent.tavily.max-results:5}")
    private int maxResults;

    @Override
    public AgentToolDefinition getDefinition() {
        return AgentToolDefinition.builder()
                .name("web_search")
                .description("互联网搜索工具。当用户询问时事新闻、最新政策、市场行情、技术进展等需要最新信息的问题时使用。使用后必须标注信息来源为【根据网上搜索结果】。")
                .parameterSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "搜索关键词或问题"
                                },
                                "max_results": {
                                    "type": "integer",
                                    "description": "返回结果数量，默认5条，最多10条",
                                    "default": 5,
                                    "minimum": 1,
                                    "maximum": 10
                                },
                                "search_depth": {
                                    "type": "string",
                                    "enum": ["basic", "advanced"],
                                    "description": "搜索深度：basic=快速搜索，advanced=深度搜索",
                                    "default": "basic"
                                }
                            },
                            "required": ["query"]
                        }
                        """)
                .requiresConfirmation(false)
                .costWeight(2)
                .build();
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, AgentContext context) {
        try {
            Map<String, Object> args = request.getArguments();
            String query = (String) args.get("query");
            Integer count = args.get("max_results") != null ?
                (Integer) args.get("max_results") : maxResults;
            String searchDepth = (String) args.getOrDefault("search_depth", "basic");

            if (query == null || query.isBlank()) {
                return ToolExecutionResult.failure("搜索内容不能为空");
            }

            // 检查 API Key
            if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
                log.warn("[WebSearch] Tavily API Key 未配置，使用模拟结果");
                return mockSearchResult(query);
            }

            log.info("[WebSearch] Tavily 搜索: query={}, count={}, depth={}", query, count, searchDepth);

            // 构建 Tavily API 请求
            Map<String, Object> requestBody = Map.of(
                "api_key", tavilyApiKey,
                "query", query,
                "search_depth", searchDepth,
                "max_results", Math.min(count, 10),
                "include_answer", true,
                "include_raw_content", false
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                tavilyEndpoint,
                HttpMethod.POST,
                entity,
                String.class
            );

            // 解析结果
            return parseTavilyResponse(response.getBody(), query);

        } catch (Exception e) {
            log.error("[WebSearch] 搜索失败", e);
            return ToolExecutionResult.failure("搜索失败: " + e.getMessage());
        }
    }

    /**
     * 解析 Tavily API 响应
     */
    private ToolExecutionResult parseTavilyResponse(String jsonResponse, String query) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);

            StringBuilder sb = new StringBuilder();
            sb.append("【网上搜索结果 - ").append(query).append("】\n\n");

            // Tavily 提供的 AI 答案摘要
            JsonNode answerNode = root.path("answer");
            if (!answerNode.isMissingNode() && !answerNode.asText().isBlank()) {
                sb.append("【搜索摘要】\n");
                sb.append(answerNode.asText()).append("\n\n");
            }

            // 搜索结果列表
            JsonNode results = root.path("results");
            if (results.isArray() && results.size() > 0) {
                sb.append("【搜索结果】\n");

                int count = 0;
                for (JsonNode item : results) {
                    String title = item.path("title").asText();
                    String url = item.path("url").asText();
                    String content = item.path("content").asText();

                    sb.append(String.format("%d. %s\n", count + 1, title));
                    sb.append(String.format("   链接: %s\n", url));
                    // 只取前 200 字的摘要
                    if (content.length() > 200) {
                        content = content.substring(0, 200) + "...";
                    }
                    sb.append(String.format("   摘要: %s\n\n", content));
                    count++;
                }

                sb.append("\n【来源标注】以上信息来自互联网搜索。");
                return ToolExecutionResult.success(sb.toString(), jsonResponse);
            }

            return ToolExecutionResult.failure("搜索无结果");

        } catch (Exception e) {
            log.error("[WebSearch] 解析响应失败", e);
            return ToolExecutionResult.failure("解析搜索结果失败: " + e.getMessage());
        }
    }

    /**
     * 模拟搜索结果（当 API Key 未配置时使用）
     */
    private ToolExecutionResult mockSearchResult(String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("【网上搜索结果 - ").append(query).append("】\n\n");
        sb.append("(模拟结果) 请配置 Tavily API Key 以获取真实的搜索结果。\n");
        sb.append("获取方式: 访问 https://app.tavily.com/home 注册并获取 API Key\n");
        sb.append("配置项: agent.tavily.api-key=tvly-...\n");
        sb.append("\n搜索关键词: ").append(query);
        return ToolExecutionResult.success(sb.toString(), Map.of("mock", true));
    }
}
