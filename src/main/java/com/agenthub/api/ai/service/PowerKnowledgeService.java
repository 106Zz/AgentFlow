package com.agenthub.api.ai.service;

import com.agenthub.api.ai.config.DashScopeRerankerConfig;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.utils.VectorStoreHelper;
import com.agenthub.api.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class PowerKnowledgeService {

        private final VectorStoreHelper vectorStoreHelper;
        private final DashScopeRerankerConfig dashScopeReranker;


        public PowerKnowledgeResult retrieve(PowerKnowledgeQuery query){
                log.info("🔍 AI 正在调用知识库工具: query={}, year={}, topK={}",
                        query.query(), query.yearFilter(), query.topK());

                long startTime = System.currentTimeMillis();

                Long userId = SecurityUtils.getUserId();
                boolean isAdmin = SecurityUtils.isAdmin();

                // 2. 向量召回 (Recall) - 故意多查一点，比如 50 条
                int userTopK = (query.topK() != null && query.topK() > 0) ? query.topK() : 5;
                int recallCount = Math.max(20, userTopK * 3); // 动态调整召回基数

                List<Document> rawDocs = vectorStoreHelper.searchWithUserFilter(
                        query.query(),
                        userId,
                        isAdmin,
                        recallCount,
                        0.45
                );
                log.debug("初排召回 {} 条文档", rawDocs.size());

                if (rawDocs.isEmpty()) {
                        return emptyResult();
                }

                // 3. 重排序 (Rerank) - 核心优化点
                List<Document> finalDocs = dashScopeReranker.rerank(
                        query.query(),
                        rawDocs,
                        userTopK
                );

                // 4. 组装结果 (Result Construction)
                // A. 提取原始切片内容 (rawContentSnippets)
                List<String> snippets = finalDocs.stream()
                        .map(Document::getText)
                        .collect(Collectors.toList());

                // B. 提取来源文件名 (sourceNames)
                // 核心逻辑：从 Document 的 metadata 里拿出 "filename" 字段，然后去重
                List<String> sources = finalDocs.stream()
                        .map(d -> d.getMetadata().getOrDefault("filename", "unknown").toString())
                        .distinct() // <--- 这里就是去重逻辑
                        .collect(Collectors.toList());

                // C. 计算最高分 (用于调试)
                double maxScore = finalDocs.stream()
                        .mapToDouble(d -> {
                                Object s = d.getMetadata().get("rerank_score");
                                return s != null ? Double.parseDouble(s.toString()) : 0.0;
                        })
                        .max()
                        .orElse(0.0);


                long totalTime = System.currentTimeMillis() - startTime;

                // 生成简报给大模型看
                // 注意：Tool 的 answer 不是最终给用户的回复，而是告诉大模型“我查到了什么”
                String answerSummary = String.format(
                        "检索成功。共找到 %d 条相关文档片段，最高置信度 %.2f。来源包括：%s",
                        finalDocs.size(), maxScore, String.join(", ", sources)
                );

                log.info("⚡️ [RAG] 检索完成。耗时: {}ms, 召回: {}, 最终: {}",
                        totalTime, rawDocs.size(), finalDocs.size());

                // 返回你定义的 Record
                return new PowerKnowledgeResult(
                        answerSummary,    // 对应 record 的 String answer
                        snippets,         // 对应 record 的 List<String> rawContentSnippets
                        sources,          // 对应 record 的 List<String> sourceNames
                        Map.of(           // 对应 record 的 Map<String, Object> debugInfo
                                "total_time_ms", totalTime,
                                "recall_count", rawDocs.size(),
                                "top_score", maxScore,
                                "rerank_model", "gte-rerank-v2"
                        )
                );

        }


        private PowerKnowledgeResult emptyResult() {
                return new PowerKnowledgeResult(
                        "未在知识库中找到相关内容。",
                        List.of(),
                        List.of(),
                        Map.of()
                );
        }



}
