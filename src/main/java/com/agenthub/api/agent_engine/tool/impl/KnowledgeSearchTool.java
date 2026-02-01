package com.agenthub.api.agent_engine.tool.impl;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    private final PowerKnowledgeService powerKnowledgeService;
    private final ObjectMapper objectMapper;

    @Override
    public AgentToolDefinition getDefinition() {
        return AgentToolDefinition.builder()
                .name("knowledge_search")
                .description("广东电力市场专属知识库。当用户询问电价、结算公式、考核规则、政策文件、技术标准时，必须使用此工具查询。")
                .parameterSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "具体的查询问题或关键词。"
                                },
                                "year": {
                                    "type": "string",
                                    "description": "年份过滤 (如 '2025', '2026')。如果用户没提，默认留空。"
                                },
                                "category": {
                                    "type": "string",
                                    "enum": ["BUSINESS", "TECHNICAL", "REGULATION"],
                                    "description": "业务分类。BUSINESS:商务/电价/费用; TECHNICAL:技术/参数; REGULATION:规则/政策。"
                                }
                            },
                            "required": ["query"]
                        }
                        """)
                .requiresConfirmation(false) // 查知识库是安全的，不需要确认
                .costWeight(1)
                .build();
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, AgentContext context) {
        try {
            Map<String, Object> args = request.getArguments();
            String query = (String) args.get("query");
            String year = (String) args.getOrDefault("year", null);
            String category = (String) args.getOrDefault("category", null);

            if (query == null || query.isBlank()) {
                return ToolExecutionResult.failure("查询内容不能为空");
            }

            log.info("[AgentTool] Invoking knowledge_search: query={}, year={}, category={}", query, year, category);

            // 构造 V1 的查询对象
            // 注意：V1 的 PowerKnowledgeQuery可能有默认值，这里显式透传
            PowerKnowledgeQuery serviceQuery = new PowerKnowledgeQuery(
                    query,
                    5, // 默认查 Top 5
                    year,
                    category
            );

            // 调用旧 Service
            PowerKnowledgeResult result = powerKnowledgeService.retrieve(serviceQuery);

            // 序列化结果供 LLM 阅读
            // PowerKnowledgeResult 包含 answer(RAG生成的答案) 和 rawContentSnippets(片段)
            // 我们主要把片段给 V2 Agent，让 V2 Agent 自己组织语言回答，或者直接用 V1 生成好的 answer
            // 策略：为了让 V2 Agent 更智能，我们把引用片段（snippets）喂给它，让它来"自省"生成。
            
            String outputJson = objectMapper.writeValueAsString(result);
            
            return ToolExecutionResult.success(outputJson, result);

        } catch (Exception e) {
            log.error("KnowledgeSearchTool execution failed", e);
            return ToolExecutionResult.failure("知识库查询失败: " + e.getMessage());
        }
    }
}
