package com.agenthub.api.agent_engine.tool.impl;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.domain.knowledge.EvidenceBlock;
import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;

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
                .requiresConfirmation(false)
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

            // 构造查询对象
            PowerKnowledgeQuery serviceQuery = new PowerKnowledgeQuery(
                    query,
                    5, // 默认查 Top 5
                    year,
                    category
            );

            // 调用服务（v2.0 返回 EvidenceBlock）
            PowerKnowledgeResult result = powerKnowledgeService.retrieve(serviceQuery);

            // 格式化输出：优先使用 EvidenceBlock，向后兼容
            String formattedOutput = formatResult(result);

            log.info("[AgentTool] knowledge_search 完成: evidenceBlocks={}, snippets={}",
                    result.getEvidenceBlockCount(), result.rawContentSnippets().size());

            return ToolExecutionResult.success(formattedOutput, result);

        } catch (Exception e) {
            log.error("KnowledgeSearchTool execution failed", e);
            return ToolExecutionResult.failure("知识库查询失败: " + e.getMessage());
        }
    }

    /**
     * 格式化检索结果（v2.0：使用 EvidenceBlock）
     * <p>原则：不摘要、不压缩、不改写原文，保留完整证据链</p>
     */
    private String formatResult(PowerKnowledgeResult result) {
        StringBuilder sb = new StringBuilder();

        // 优先使用 EvidenceBlock
        List<EvidenceBlock> blocks = result.evidenceBlocks();
        if (blocks != null && !blocks.isEmpty()) {
            sb.append("【知识库检索结果】共找到 ").append(blocks.size())
              .append(" 个证据块（").append(result.rawContentSnippets().size()).append(" 个文档片段）\n\n");

            for (int i = 0; i < blocks.size(); i++) {
                EvidenceBlock block = blocks.get(i);
                sb.append(formatEvidenceBlock(block, i + 1));
            }
        } else {
            // 降级：使用 rawContentSnippets
            sb.append("【知识库检索结果】共找到 ")
              .append(result.rawContentSnippets().size()).append(" 条相关内容\n\n");

            for (int i = 0; i < result.rawContentSnippets().size(); i++) {
                sb.append("[片段 ").append(i + 1).append("] ");
                sb.append(result.rawContentSnippets().get(i));
                sb.append("\n\n---\n\n");
            }
        }

        // 添加来源文件列表（带下载链接，使用 Markdown 格式方便 LLM 复制）
        if (result.sources() != null && !result.sources().isEmpty()) {
            sb.append("【参考来源】\n");
            int idx = 1;
            for (PowerKnowledgeResult.SourceDocument source : result.sources()) {
                String url = source.downloadUrl();
                // 使用 Markdown 格式：[文件名](URL)
                // 这样 LLM 在引用时可以更容易复制格式
                if (url != null && !url.isEmpty()) {
                    sb.append(String.format("%d. [%s](%s)\n", idx++, source.filename(), url));
                } else {
                    sb.append(String.format("%d. %s（下载链接生成失败）\n", idx++, source.filename()));
                }
            }
            log.info("[KnowledgeSearchTool] 来源文件数量: {}, 第一个文件链接: {}",
                    result.sources().size(),
                    result.sources().get(0).downloadUrl());
        }

        return sb.toString();
    }

    /**
     * 格式化单个 EvidenceBlock
     */
    private String formatEvidenceBlock(EvidenceBlock block, int index) {
        StringBuilder sb = new StringBuilder();

        // 标题行：证据编号 + 来源引用
        sb.append("┌────────────────────────────────────────\n");
        sb.append(String.format("| [证据 %d] %s\n", index, block.getSourceReference()));
        sb.append("├────────────────────────────────────────\n");

        // 元信息行
        sb.append("| ");
        if (block.chunkCount() > 1) {
            sb.append("合并 ").append(block.chunkCount()).append(" 个片段 | ");
        }
        sb.append("支持度: ").append(String.format("%.2f", block.supportScore()));
        if (block.type() != null) {
            sb.append(" | 类型: ").append(block.type().name());
        }
        sb.append("\n");
        sb.append("├────────────────────────────────────────\n");

        // 内容（原文，不改写）
        String content = block.content();
        // 为了让 LLM 更好地解析，每行加上 "| " 前缀
        sb.append("| \n");
        for (String line : content.split("\n")) {
            sb.append("| ").append(line).append("\n");
        }
        sb.append("|\n");
        sb.append("└────────────────────────────────────────\n\n");

        return sb.toString();
    }
}
