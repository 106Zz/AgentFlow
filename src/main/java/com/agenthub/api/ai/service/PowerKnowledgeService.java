package com.agenthub.api.ai.service;

import com.agenthub.api.ai.config.DashScopeRerankerConfig;
import com.agenthub.api.ai.domain.knowledge.EvidenceBlock;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.utils.VectorStoreHelper;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.search.dto.req.HybridSearchRequest;
import com.agenthub.api.search.dto.result.HybridSearchResult;
import com.agenthub.api.search.service.IHybridSearchService;
import com.aliyun.oss.OSS;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class PowerKnowledgeService {

        private final VectorStoreHelper vectorStoreHelper;
        private final DashScopeRerankerConfig dashScopeReranker;
        private final IHybridSearchService hybridSearchService;
        private final OSS ossClient;
        private final EvidenceAssembly evidenceAssembly;  // 新增：证据组装器
        private final com.agenthub.api.knowledge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;  // 用于查询正确的 OSS 路径
        private final QueryRewriteService queryRewriteService;  // 查询改写服务
        private final RAGCacheService ragCacheService;  // RAG 缓存服务

        @Value("${aliyun.oss.bucketName:agenthub-knowledge}")
        private String bucketName;

        @Value("${knowledge.retrieval.enable-hybrid:false}")  // 新增配置
        private boolean enableHybridSearch;

        @Value("${knowledge.retrieval.top-k:5}")
        private int defaultTopK;

        @Value("${knowledge.retrieval.enable-cache:true}")
        private boolean enableCache;


        public PowerKnowledgeResult retrieve(PowerKnowledgeQuery query){
                long startTime = System.currentTimeMillis();
                String userQuery = query.query();

                // 1. 解析参数
                int userTopK = query.topK() != null ? query.topK() : defaultTopK;
                Long userId = SecurityUtils.getUserId();
                boolean isAdmin = SecurityUtils.isAdmin();

                log.info("【知识检索】查询: {}, topK: {}, 混合检索: {}, 缓存: {}",
                        userQuery, userTopK, enableHybridSearch, enableCache);

                // 2. 尝试从缓存获取
                if (enableCache) {
                        PowerKnowledgeResult cachedResult = ragCacheService.get(userQuery, userId, null, userTopK);
                        if (cachedResult != null) {
                                log.info("【知识检索】缓存命中，直接返回: query={}", userQuery);
                                return cachedResult;
                        }
                }

                // 3. 查询改写（Query Rewrite）
                List<String> rewrittenQueries = queryRewriteService.rewrite(userQuery);
                log.info("【查询改写】改写后查询数量: {}, queries: {}", rewrittenQueries.size(), rewrittenQueries);

                // 4. 向量召回 (Recall) - 故意多查一点，比如 50 条
                // 4. 召回阶段（Vector + BM25）- 支持多查询召回
                List<Document> rawDocs;
                if (enableHybridSearch) {
                        rawDocs = multiQueryHybridRecall(rewrittenQueries, userId, isAdmin, userTopK);
                } else {
                        rawDocs = multiQueryVectorRecall(rewrittenQueries, userId, isAdmin, userTopK);
                }

                log.info("【知识检索】召回完成，共 {} 条候选", rawDocs.size());


                // 4. 重排阶段（Reranker）
                List<Document> finalDocs = dashScopeReranker.rerank(
                        userQuery,
                        rawDocs,
                        userTopK
                );

                log.info("【知识检索】重排完成，保留 {} 条", finalDocs.size());

                // 5. 组装结果
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("【知识检索】完成，总耗时: {}ms", elapsed);

                PowerKnowledgeResult result = buildResult(finalDocs, userQuery, elapsed);

                // 6. 存入缓存
                if (enableCache) {
                        ragCacheService.put(userQuery, userId, null, userTopK, result);
                }

                return result;

        }


        /**
         * 混合召回（向量 + BM25）
         */
        private List<Document> hybridRecall(
                String query,
                Long userId,
                boolean isAdmin,
                int topK) {

                log.debug("【混合召回】开始混合检索");

                // 构建混合检索请求
                HybridSearchRequest request = new HybridSearchRequest();
                request.setQuery(query);
                request.setTopK(topK);
                request.setUserId(userId);
                request.setIsAdmin(isAdmin);

                // 召回参数：召回 topK * 3 条，供 Reranker 精排
                int recallCount = Math.max(20, topK * 3);
                request.setVectorTopK(recallCount);  // 向量召回数量
                request.setBm25TopK(recallCount);    // BM25 召回数量

                // 使用 RRF 融合
                request.setFusionStrategy(HybridSearchRequest.FusionStrategy.RRF);
                request.setRrfK(60);

                // 执行混合检索
                List<HybridSearchResult> results = hybridSearchService.hybridSearch(request);

                // 转换为 Document
                return results.stream()
                        .map(this::toDocument)
                        .collect(Collectors.toList());
        }


        /**
         * 多查询混合召回（Query Rewrite 后的合并召回）
         * <p>
         * 对每个改写后的查询分别进行检索，然后合并结果
         * 使用 Map 去重，保留分数最高的文档
         * </p>
         */
        private List<Document> multiQueryHybridRecall(
                List<String> queries,
                Long userId,
                boolean isAdmin,
                int topK) {

                log.info("【多查询混合召回】开始，查询数量: {}", queries.size());

                // 使用 Map 进行去重，key 是 doc id，value 是 document 和最高分数
                Map<String, DocumentWrapper> docMap = new HashMap<>();

                // 对每个查询分别进行检索
                for (String query : queries) {
                        try {
                                List<Document> docs = singleQueryHybridRecall(query, userId, isAdmin, topK);

                                for (Document doc : docs) {
                                        String docId = doc.getId();
                                        double score = getDocumentScore(doc);

                                        // 如果文档已存在，保留分数更高的
                                        DocumentWrapper existing = docMap.get(docId);
                                        if (existing == null || score > existing.score) {
                                                docMap.put(docId, new DocumentWrapper(doc, score));
                                        }
                                }
                        } catch (Exception e) {
                                log.warn("【多查询混合召回】单个查询失败: {}, 错误: {}", query, e.getMessage());
                        }
                }

                // 按分数排序，取 Top K
                List<Document> result = docMap.values().stream()
                        .sorted((a, b) -> Double.compare(b.score, a.score))
                        .limit(topK * 3)  // 多召回一些，供 Reranker 精排
                        .map(w -> w.document)
                        .collect(Collectors.toList());

                log.info("【多查询混合召回】完成，去重后文档数: {}", result.size());
                return result;
        }

        /**
         * 单查询混合召回（内部方法）
         */
        private List<Document> singleQueryHybridRecall(
                String query,
                Long userId,
                boolean isAdmin,
                int topK) {

                // 构建混合检索请求
                HybridSearchRequest request = new HybridSearchRequest();
                request.setQuery(query);
                request.setTopK(topK);
                request.setUserId(userId);
                request.setIsAdmin(isAdmin);

                // 每个查询召回较少的结果，因为会合并多个查询的结果
                int recallCount = Math.max(10, topK);
                request.setVectorTopK(recallCount);
                request.setBm25TopK(recallCount);

                // 使用 RRF 融合
                request.setFusionStrategy(HybridSearchRequest.FusionStrategy.RRF);
                request.setRrfK(60);

                List<HybridSearchResult> results = hybridSearchService.hybridSearch(request);

                return results.stream()
                        .map(this::toDocument)
                        .collect(Collectors.toList());
        }

        /**
         * 多查询向量召回（Query Rewrite 后的合并召回）
         */
        private List<Document> multiQueryVectorRecall(
                List<String> queries,
                Long userId,
                boolean isAdmin,
                int topK) {

                log.info("【多查询向量召回】开始，查询数量: {}", queries.size());

                Map<String, DocumentWrapper> docMap = new HashMap<>();

                for (String query : queries) {
                        try {
                                List<Document> docs = singleQueryVectorRecall(query, userId, isAdmin, topK);

                                for (Document doc : docs) {
                                        String docId = doc.getId();
                                        double score = getDocumentScore(doc);

                                        DocumentWrapper existing = docMap.get(docId);
                                        if (existing == null || score > existing.score) {
                                                docMap.put(docId, new DocumentWrapper(doc, score));
                                        }
                                }
                        } catch (Exception e) {
                                log.warn("【多查询向量召回】单个查询失败: {}, 错误: {}", query, e.getMessage());
                        }
                }

                List<Document> result = docMap.values().stream()
                        .sorted((a, b) -> Double.compare(b.score, a.score))
                        .limit(topK * 3)
                        .map(w -> w.document)
                        .collect(Collectors.toList());

                log.info("【多查询向量召回】完成，去重后文档数: {}", result.size());
                return result;
        }

        /**
         * 单查询向量召回（内部方法）
         */
        private List<Document> singleQueryVectorRecall(
                String query,
                Long userId,
                boolean isAdmin,
                int topK) {

                int recallCount = Math.max(10, topK);

                return vectorStoreHelper.searchWithUserFilter(
                        query,
                        userId,
                        isAdmin,
                        recallCount,
                        0.45,
                        null
                );
        }

        /**
         * 从 document 的 metadata 中获取分数
         */
        private double getDocumentScore(Document doc) {
                // 优先使用 hybrid_score
                Object hybridScore = doc.getMetadata().get("hybrid_score");
                if (hybridScore != null) {
                        return ((Number) hybridScore).doubleValue();
                }

                // 其次使用 vector_score
                Object vectorScore = doc.getMetadata().get("vector_score");
                if (vectorScore != null) {
                        return ((Number) vectorScore).doubleValue();
                }

                return 0.0;
        }

        /**
         * 文档包装类，用于保存文档和分数
         */
        private static class DocumentWrapper {
                Document document;
                double score;

                DocumentWrapper(Document document, double score) {
                        this.document = document;
                        this.score = score;
                }
        }


        /**
         * 纯向量召回（原有逻辑）
         */
        private List<Document> vectorRecall(
                String query,
                Long userId,
                boolean isAdmin,
                int topK) {

                int recallCount = Math.max(20, topK * 3);

                return vectorStoreHelper.searchWithUserFilter(
                        query,
                        userId,
                        isAdmin,
                        recallCount,
                        0.45,  // similarityThreshold
                        null   // category
                );
        }

        /**
         * 将 HybridSearchResult 转换为 Document
         */
        private Document toDocument(HybridSearchResult result) {
                Document doc = new Document(
                        result.getContent(),
                        result.getMetadata()
                );
                // 设置 ID
                doc.getMetadata().put("internal_id", result.getDocId());

                // 保留分数信息（用于调试）
                if (result.getScore() != null) {
                        doc.getMetadata().put("hybrid_score", result.getScore());
                }
                if (result.getVectorScore() != null) {
                        doc.getMetadata().put("vector_score", result.getVectorScore());
                }
                if (result.getBm25Score() != null) {
                        doc.getMetadata().put("bm25_score", result.getBm25Score());
                }

                return doc;
        }


        /**
         * 组装返回结果（v2.0：包含 EvidenceBlock）
         */
        private PowerKnowledgeResult buildResult(
                List<Document> documents,
                String query,
                long elapsedMs) {

                if (documents.isEmpty()) {
                        return emptyResult();
                }

                // ========== v2.0 新增：证据组装 ==========
                // 将 rerank 后的 chunks 组装成语义完整的 EvidenceBlock
                List<EvidenceBlock> evidenceBlocks = evidenceAssembly.assemble(documents);
                log.info("[PowerKnowledgeService] 证据组装完成：{} chunks -> {} evidence blocks",
                        documents.size(), evidenceBlocks.size());

                // A. 提取原始切片内容 (rawContentSnippets) - 向后兼容
                List<String> snippets = documents.stream()
                        .map(Document::getText)
                        .collect(Collectors.toList());

                // B. 提取来源 + 生成 OSS 链接
                // 核心逻辑：优先使用 metadata 中的 file_path（OSS完整路径）
                // 如果 file_path 不是有效的 OSS 路径（旧数据），则从数据库查询正确的路径
                List<PowerKnowledgeResult.SourceDocument> sources = documents.stream()
                        .map(d -> {
                                String filename = d.getMetadata().getOrDefault("filename", "unknown").toString();
                                String filePath = d.getMetadata().getOrDefault("file_path", "").toString();
                                boolean validPath = false;

                                // 检查 file_path 是否是有效的 OSS 路径
                                // 有效路径应该以 "knowledge/" 开头
                                if (filePath.startsWith("knowledge/")) {
                                        validPath = true;
                                } else {
                                        // 旧数据：file_path 只是文件名，需要从数据库查询正确的 OSS 路径
                                        String knowledgeIdStr = d.getMetadata().getOrDefault("knowledge_id", "").toString();
                                        if (!knowledgeIdStr.isEmpty()) {
                                                try {
                                                        Long knowledgeId = Long.parseLong(knowledgeIdStr);
                                                        var kb = knowledgeBaseMapper.selectById(knowledgeId);
                                                        if (kb != null && kb.getFilePath() != null && kb.getFilePath().startsWith("knowledge/")) {
                                                                filePath = kb.getFilePath();
                                                                validPath = true;
                                                                log.debug("[PowerKnowledgeService] 从数据库查询到正确的 OSS 路径: knowledgeId={}, filePath={}",
                                                                        knowledgeId, filePath);
                                                        }
                                                } catch (Exception e) {
                                                        log.warn("[PowerKnowledgeService] 查询数据库失败: {}", e.getMessage());
                                                }
                                        }
                                }

                                String signedUrl = "";
                                if (validPath) {
                                        signedUrl = generatePresignedUrl(filePath);
                                }

                                log.info("[PowerKnowledgeService] 生成 OSS 链接: filename={}, filePath={}, url={}, validPath={}",
                                        filename, filePath,
                                        signedUrl.isEmpty() ? "(空)" : signedUrl.substring(0, Math.min(50, signedUrl.length())),
                                        validPath);

                                return new PowerKnowledgeResult.SourceDocument(filename, signedUrl);
                        })
                        .distinct()  // 根据完整路径去重
                        .collect(Collectors.toList());

                log.info("[PowerKnowledgeService] sources 列表: {}", sources);

                // C. 计算最高分 (用于调试)
                double maxScore = documents.stream()
                        .mapToDouble(d -> {
                                Object s = d.getMetadata().get("rerank_score");
                                return s != null ? Double.parseDouble(s.toString()) : 0.0;
                        })
                        .max()
                        .orElse(0.0);

                // D. 生成简报给大模型看
                // 注意：answer 不是最终给用户的回复，而是告诉大模型"我查到了什么"
                String sourceNamesStr = sources.stream()
                        .map(PowerKnowledgeResult.SourceDocument::filename)
                        .collect(Collectors.joining(", "));

                String answerSummary = String.format(
                        "检索成功。共找到 %d 条相关文档片段，组装成 %d 个证据块，最高支持度 %.2f。来源包括：%s",
                        documents.size(), evidenceBlocks.size(), maxScore, sourceNamesStr
                );

                log.info("⚡️ [RAG] 检索完成。耗时: {}ms, 最终: {} 条, 证据块: {} 个",
                        elapsedMs, documents.size(), evidenceBlocks.size());

                // E. 返回 v2.0 格式的结果（包含 evidenceBlocks）
                return new PowerKnowledgeResult(
                        answerSummary,              // answer: 给大模型的摘要
                        snippets,                   // rawContentSnippets: 原始切片
                        sources,                    // sources: 来源文件 + 下载链接
                        Map.of(                     // debugInfo: 调试信息
                                "total_time_ms", elapsedMs,
                                "top_score", maxScore
                        ),
                        evidenceBlocks              // v2.0 新增：证据块列表
                );
        }

        /**
         * 空结果
         */
        private PowerKnowledgeResult emptyResult() {
                return new PowerKnowledgeResult(
                        "未在知识库中找到相关内容。",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of()  // evidenceBlocks 为空
                );
        }


        /**
         * 生成 OSS 临时授权链接
         * 让前端用户可以下载私有 Bucket 里的文件，链接有效期 1 小时
         */
        private String generatePresignedUrl(String objectName) {
                try {
                        // 设置 URL 过期时间为 1 小时
                        Date expiration = new Date(new Date().getTime() + 3600 * 1000);

                        log.info("[PowerKnowledgeService] 开始生成 OSS 链接: bucket={}, object={}", bucketName, objectName);

                        // 生成 URL
                        URL url = ossClient.generatePresignedUrl(bucketName, objectName, expiration);
                        String urlString = url.toString();
                        log.info("[PowerKnowledgeService] OSS 链接生成成功: {}", urlString);
                        return urlString;
                } catch (Exception e) {
                        log.error("[PowerKnowledgeService] 生成 OSS 签名链接失败: bucket={}, object={}, error={}",
                                bucketName, objectName, e.getMessage(), e);
                        return "";
                }
        }



}
