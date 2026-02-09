package com.agenthub.api.search.service.impl;


import com.agenthub.api.search.dto.req.Bm25SearchRequest;
import com.agenthub.api.search.dto.req.HybridSearchRequest;
import com.agenthub.api.search.dto.result.Bm25SearchResult;
import com.agenthub.api.search.dto.result.HybridSearchResult;
import com.agenthub.api.search.service.IBm25SearchService;
import com.agenthub.api.search.service.IHybridSearchService;
import com.agenthub.api.search.util.RRFFusion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 混合检索服务实现
 *
 * <p>功能：</p>
 * <ul>
 *   <li>向量检索（语义相似度）</li>
 *   <li>BM25检索（关键词匹配）</li>
 *   <li>RRF融合（推荐）</li>
 *   <li>加权融合（可选）</li>
 * </ul>
 *
 * <p>融合策略说明：</p>
 * <ul>
 *   <li>RRF：只看排名，不看分数，简单稳健</li>
 *   <li>WEIGHTED：需要标准化，保留分数差异，适合调优</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchServiceImpl implements IHybridSearchService {

    private final VectorStore vectorStore;
    private final IBm25SearchService bm25SearchService;
    private final RRFFusion rrfFusion;
    private final Executor hybridSearchExecutor;  // 由 Spring 按名称注入

    /**
     * 混合检索入口
     *
     * @param request 检索请求
     * @return 融合后的结果列表
     */
    @Override
    public List<HybridSearchResult> hybridSearch(HybridSearchRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("【混合检索】开始: query='{}', strategy={}", request.getQuery(), request.getFusionStrategy());

        // 根据策略选择融合方式
        List<HybridSearchResult> results = switch (request.getFusionStrategy()) {
            case RRF -> rrfFusion(request);
            case WEIGHTED -> weightedFusion(request);
        };

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("【混合检索】完成: query='{}', returned={}, elapsed={}ms",
                request.getQuery(), results.size(), elapsed);

        return results;
    }

    /**
     * 简化的检索方法（便捷入口）
     */
    @Override
    public List<HybridSearchResult> hybridSearch(String query, int topK, Long knowledgeId, Long userId, boolean isAdmin) {
        HybridSearchRequest request = new HybridSearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        request.setKnowledgeId(knowledgeId);
        request.setUserId(userId);
        request.setIsAdmin(isAdmin);

        return hybridSearch(request);
    }

    // ==================== 融合策略实现 ====================

    /**
     * RRF（Reciprocal Rank Fusion）融合
     *
     * <p>公式：score(d) = Σ 1/(k + rank_i(d))</p>
     * <p>优势：只看排名，不看分数，分数量纲不同时也能工作</p>
     */
    private List<HybridSearchResult> rrfFusion(HybridSearchRequest request) {
        long startTime = System.currentTimeMillis();

        // 并行执行两种检索（使用混合检索专用线程池）
        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return doVectorSearch(request);
            } catch (Exception e) {
                log.warn("【混合检索】向量检索失败: {}", request.getQuery(), e);
                return Collections.emptyList();
            }
        }, hybridSearchExecutor);

        // 方法2：BM25检索 - 调用已实现的异步方法
        CompletableFuture<List<Bm25SearchResult>> bm25Future = bm25SearchService.searchAsync(
                convertToBm25Request(request)
        );

        // 等待完成（超时从 10 秒增加到 20 秒，给 BM25 检索更多时间）
        // 注意：配合数据库索引优化和连接池配置，BM25 检索应能在 2-3 秒内完成
        try {
            CompletableFuture.allOf(vectorFuture, bm25Future)
                    .get(20, TimeUnit.SECONDS);

            List<Document> vectorResults = vectorFuture.get();
            List<Bm25SearchResult> bm25Results = bm25Future.get();

            // RRF 融合（原有逻辑）
            int k = request.getRrfK() != null ? request.getRrfK() : 60;
            List<HybridSearchResult> fused = rrfFusion.fuse(
                    vectorResults,
                    bm25Results,
                    k,
                    request.getTopK()
            );

            // 填充缺失内容
            fillMissingContent(fused, vectorResults, bm25Results);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("【混合检索-RRF】完成: query='{}', returned={}, elapsed={}ms",
                    request.getQuery(), fused.size(), elapsed);

            return fused;

        } catch (TimeoutException e) {
            log.error("【混合检索-RRF】超时: {}", request.getQuery());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("【混合检索-RRF】执行异常", e);
            throw new RuntimeException("混合检索失败", e);
        }
    }

    /**
     * 加权融合
     *
     * <p>流程：提取分数 → Min-Max标准化 → 加权求和</p>
     * <p>优势：保留分数绝对差异，可调节权重</p>
     * <p>劣势：依赖标准化步骤，计算更复杂</p>
     */
    private List<HybridSearchResult> weightedFusion(HybridSearchRequest request) {
        long startTime = System.currentTimeMillis();


        // 并行执行两种检索（使用混合检索专用线程池）
        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return doVectorSearch(request);
            } catch (Exception e) {
                log.warn("【混合检索】向量检索失败: {}", request.getQuery(), e);
                return Collections.emptyList();
            }
        }, hybridSearchExecutor);

        CompletableFuture<List<Bm25SearchResult>> bm25Future = bm25SearchService.searchAsync(
                convertToBm25Request(request)
        );


        // 等待完成（超时从 10 秒增加到 20 秒，给 BM25 检索更多时间）
        // 注意：配合数据库索引优化和连接池配置，BM25 检索应能在 2-3 秒内完成
        try {
            CompletableFuture.allOf(vectorFuture, bm25Future)
                    .get(20, TimeUnit.SECONDS);

            List<Document> vectorResults = vectorFuture.get();
            List<Bm25SearchResult> bm25Results = bm25Future.get();

            // 步骤2：提取原始分数
            Map<String, Double> vectorScores = vectorResults.stream()
                    .collect(Collectors.toMap(
                            Document::getId,
                            doc -> getSimilarityScore(doc)
                    ));

            Map<String, Double> bm25Scores = bm25Results.stream()
                    .collect(Collectors.toMap(
                            Bm25SearchResult::getDocId,
                            Bm25SearchResult::getScore
                    ));

            // 步骤3：Min-Max标准化到 [0, 1]
            Map<String, Double> normalizedVector = rrfFusion.normalize(vectorScores);
            Map<String, Double> normalizedBm25 = rrfFusion.normalize(bm25Scores);

            // 步骤4：加权融合
            double bm25Weight = request.getBm25Weight();
            Map<String, Double> fusedScores = rrfFusion.weightedFuse(
                    normalizedVector,
                    normalizedBm25,
                    bm25Weight
            );

            // 步骤5：构建查找映射（用于填充内容）
            Map<String, Document> vectorMap = vectorResults.stream()
                    .collect(Collectors.toMap(Document::getId, doc -> doc));

            Map<String, Bm25SearchResult> bm25Map = bm25Results.stream()
                    .collect(Collectors.toMap(
                            Bm25SearchResult::getDocId,
                            result -> result
                    ));

            // 步骤6：构建最终结果
            List<HybridSearchResult> results = fusedScores.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .map(e -> {
                        HybridSearchResult result = new HybridSearchResult();
                        result.setDocId(e.getKey());
                        result.setScore(e.getValue());

                        // 填充内容和元数据
                        fillResultContent(result, vectorMap, bm25Map);

                        return result;
                    })
                    .collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("【混合检索-Weighted】完成: query='{}', returned={}, elapsed={}ms",
                    request.getQuery(), results.size(), elapsed);

            return results;

        } catch (TimeoutException e) {
            log.error("【混合检索-Weighted】超时: {}", request.getQuery());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("【混合检索-Weighted】执行异常", e);
            throw new RuntimeException("混合检索失败", e);
        }
    }

    // ==================== 检索器实现 ====================

    /**
     * 执行向量检索
     *
     * <p>使用 Spring AI 的 VectorStore 接口</p>
     * <p>支持 knowledge_id 过滤</p>
     */
    private List<Document> doVectorSearch(HybridSearchRequest request) {
        // 使用正确的内部类名：Builder 而非 SearchRequestBuilder
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.getQuery())
                .topK(request.getVectorTopK())
                .similarityThreshold(0.0); // 显式设置阈值为0，避免默认值过高导致无结果

        // 构建过滤表达式
        StringBuilder filter = new StringBuilder();

        // 1. 知识库过滤 (Priority 1)
        if (request.getKnowledgeId() != null) {
            filter.append("knowledge_id == '").append(request.getKnowledgeId()).append("'");
        }

        // 2. 权限过滤 (Priority 2)
        // 只有非管理员需要检查权限
        if (request.getIsAdmin() != null && !request.getIsAdmin()) {
            if (!filter.isEmpty()) {
                filter.append(" && ");
            }

            // 权限逻辑：(是公开的) OR (是自己的)
            // (user_id == '0' && is_public == '1') || user_id == '123'
            filter.append("( (user_id == '0' && is_public == '1') || user_id == '")
                  .append(request.getUserId())
                  .append("' )");
        }

        // 应用过滤器
        String filterExpr = filter.toString();
        if (!filterExpr.isEmpty()) {
            builder.filterExpression(filterExpr);
        }

        try {
            return vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            log.warn("【混合检索】向量检索失败: {}", request.getQuery(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 执行BM25检索
     *
     * <p>使用自建的 BM25 索引</p>
     * <p>支持用户权限过滤</p>
     */
    private List<Bm25SearchResult> doBm25Search(HybridSearchRequest request) {
        Bm25SearchRequest bm25Request = new Bm25SearchRequest();
        bm25Request.setQuery(request.getQuery());
        bm25Request.setTopK(request.getBm25TopK());
        bm25Request.setKnowledgeId(request.getKnowledgeId());
        bm25Request.setUserId(request.getUserId());
        bm25Request.setIsAdmin(request.getIsAdmin());

        try {
            return bm25SearchService.search(bm25Request);
        } catch (Exception e) {
            log.warn("【混合检索】BM25检索失败: {}", request.getQuery(), e);
            return Collections.emptyList();
        }
    }

    // ==================== 内容填充 ====================

    /**
     * 批量填充缺失的内容
     *
     * <p>RRF融合时，部分结果可能没有完整的内容和元数据</p>
     * <p>需要从原始检索结果中补充</p>
     */
    private void fillMissingContent(
            List<HybridSearchResult> fused,
            List<Document> vectorResults,
            List<Bm25SearchResult> bm25Results) {

        // 构建查找映射（提高查找效率）
        Map<String, Document> vectorMap = vectorResults.stream()
                .collect(Collectors.toMap(Document::getId, doc -> doc));

        Map<String, Bm25SearchResult> bm25Map = bm25Results.stream()
                .collect(Collectors.toMap(
                        Bm25SearchResult::getDocId,
                        result -> result
                ));

        // 遍历融合结果，填充缺失内容
        for (HybridSearchResult result : fused) {
            fillResultContent(result, vectorMap, bm25Map);
        }
    }

    /**
     * 填充单个结果的内容和元数据
     *
     * <p>优先级：BM25结果 > 向量结果</p>
     * <p>因为BM25结果包含匹配的词项信息</p>
     */
    private void fillResultContent(
            HybridSearchResult result,
            Map<String, Document> vectorMap,
            Map<String, Bm25SearchResult> bm25Map) {

        String docId = result.getDocId();

        // 如果已经有内容，跳过
        if (result.getContent() != null && !result.getContent().isEmpty()) {
            return;
        }

        // 优先从BM25结果获取（包含匹配词项）
        Bm25SearchResult bm25Result = bm25Map.get(docId);
        if (bm25Result != null && bm25Result.getContent() != null) {
            result.setContent(bm25Result.getContent());
            result.setMetadata(bm25Result.getMetadata());
            return;
        }

        // 其次从向量结果获取
        Document vectorDoc = vectorMap.get(docId);
        if (vectorDoc != null) {
            result.setContent(vectorDoc.getText());
            result.setMetadata(vectorDoc.getMetadata());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 获取文档的相似度分数
     *
     * <p>Spring AI 的 Document 不直接存储相似度分数</p>
     * <p>需要从 metadata 中提取 distance 字段</p>
     * <p>distance 越小表示越相似，需要转换为相似度</p>
     *
     * @param doc 文档对象
     * @return 相似度分数（0-1，1表示最相似）
     */
    private Double getSimilarityScore(Document doc) {
        // PGVector 返回的是 distance（余弦距离 = 1 - 余弦相似度）
        Object score = doc.getMetadata().get("distance");
        if (score instanceof Number) {
            double distance = ((Number) score).doubleValue();
            // 转换为相似度
            return 1.0 - distance;
        }
        return 1.0;  // 默认最高分数（当无法获取distance时）
    }

    /**
     * 转换为 BM25 请求
     */
    private Bm25SearchRequest convertToBm25Request(HybridSearchRequest request) {
        Bm25SearchRequest bm25Request = new Bm25SearchRequest();
        bm25Request.setQuery(request.getQuery());
        bm25Request.setTopK(request.getBm25TopK());
        bm25Request.setKnowledgeId(request.getKnowledgeId());
        bm25Request.setUserId(request.getUserId());
        bm25Request.setIsAdmin(request.getIsAdmin());
        return bm25Request;
    }
}
