package com.agenthub.api.search.service.impl;


import com.agenthub.api.search.domain.Bm25DocFreq;
import com.agenthub.api.search.domain.Bm25Index;
import com.agenthub.api.search.domain.Bm25Stats;
import com.agenthub.api.search.domain.Bm25TermFreq;
import com.agenthub.api.search.domain.VectorStoreDoc;
import com.agenthub.api.search.dto.req.Bm25SearchRequest;
import com.agenthub.api.search.dto.result.Bm25SearchResult;
import com.agenthub.api.search.mapper.Bm25DocFreqMapper;
import com.agenthub.api.search.mapper.Bm25IndexMapper;
import com.agenthub.api.search.mapper.Bm25StatsMapper;
import com.agenthub.api.search.mapper.Bm25TermFreqMapper;
import com.agenthub.api.search.mapper.VectorStoreDocMapper;
import com.agenthub.api.search.service.IBm25SearchService;
import com.agenthub.api.search.util.ChineseTokenizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * BM25检索服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Bm25SearchServiceImpl implements IBm25SearchService {

    private final Bm25IndexMapper indexMapper;
    private final Bm25TermFreqMapper termFreqMapper;
    private final Bm25DocFreqMapper docFreqMapper;
    private final Bm25StatsMapper statsMapper;
    private final ChineseTokenizer tokenizer;
    private final PgVectorStore pgVectorStore;
    private final VectorStoreDocMapper vectorStoreDocMapper;  // 批量查询 vector_store 表

    /**
     * BM25 参数
     */
    private static final double K1 = 1.5;  // 词频饱和参数
    private static final double B = 0.75;  // 长度惩罚参数


    @Override
    public List<Bm25SearchResult> search(Bm25SearchRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("【BM25检索】开始: query='{}'", request.getQuery());

        // 步骤1：对查询进行分词
        List<String> queryTokens = tokenizer.tokenize(request.getQuery());
        if (queryTokens.isEmpty()) {
            log.warn("【BM25检索】查询分词为空: {}", request.getQuery());
            return Collections.emptyList();
        }

        // 步骤2：获取全局统计
        Bm25Stats totalDocsStats = statsMapper.selectById("total_docs");
        Bm25Stats avgLengthStats = statsMapper.selectById("avg_doc_length");

        if (totalDocsStats == null || totalDocsStats.getValue() == 0) {
            log.warn("【BM25检索】没有索引数据");
            return Collections.emptyList();
        }

        int totalDocs = totalDocsStats.getValue().intValue();
        double avgDocLength = avgLengthStats.getValue();

        // 步骤3：获取查询词的文档频率
        List<String> uniqueTerms = queryTokens.stream().distinct().toList();
        List<Bm25DocFreq> docFreqs = docFreqMapper.selectList(
                new LambdaQueryWrapper<Bm25DocFreq>()
                        .in(Bm25DocFreq::getTerm, uniqueTerms)
        );

        Map<String, Integer> docFreqMap = docFreqs.stream()
                .collect(Collectors.toMap(
                        Bm25DocFreq::getTerm,
                        Bm25DocFreq::getDocCount
                ));

        // 步骤4：计算每个文档的 BM25 分数
        Map<String, BM25Score> scores = calculateScores(
                queryTokens, docFreqMap, totalDocs, avgDocLength, request
        );

        // 步骤5：排序并过滤
        List<Bm25SearchResult> results = scores.entrySet().stream()
                .filter(e -> e.getValue().score() >= request.getMinScore())
                .sorted(Map.Entry.<String, BM25Score>comparingByValue(
                        (a, b) -> Double.compare(b.score(), a.score())
                ))
                .limit(request.getTopK())
                .map(e -> {
                    BM25Score score = e.getValue();
                    Bm25SearchResult result = new Bm25SearchResult();
                    result.setDocId(e.getKey());
                    result.setScore(score.score());
                    result.setMatchedTerms(score.matchedTerms());

                    // 获取文档内容和元数据
                    // 这里需要从 vector_store 获取
                    result.setContent("...");  // 稍后填充
                    result.setMetadata(new HashMap<>());

                    return result;
                })
                .collect(Collectors.toList());

        // 步骤6：填充文档内容
        if (!results.isEmpty()) {
            fillDocumentContent(results);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("【BM25检索】完成: query='{}', returned={}, elapsed={}ms",
                request.getQuery(), results.size(), elapsed);

        return results;
    }



    @Override
    public List<Bm25SearchResult> search(String query, int topK, Long knowledgeId, Long userId, boolean isAdmin) {
        Bm25SearchRequest request = new Bm25SearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        request.setKnowledgeId(knowledgeId);
        request.setUserId(userId);
        request.setIsAdmin(isAdmin);
        return search(request);
    }

    /**
     * 异步检索方法
     */
    @Async("hybridSearchExecutor")  // 使用指定的线程池
    @Override
    public CompletableFuture<List<Bm25SearchResult>> searchAsync(Bm25SearchRequest request) {
        try {
            List<Bm25SearchResult> results = search(request);  // 调用原有同步方法
            return CompletableFuture.completedFuture(results);
        } catch (Exception e) {
            log.error("【BM25异步检索】失败: {}", request.getQuery(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 计算 BM25 分数
     * <p>
     * 核心算法逻辑：遍历查询词，累加它们在各个文档中的得分。
     * <p>
     * 性能优化：批量查询 bm25_index 表，避免 N+1 查询问题
     *
     * @param queryTokens  分词后的查询词列表（例如：["光伏", "发电"]）
     * @param docFreqMap   词的文档频率映射（Key: 词, Value: 包含该词的文档数）。用于计算 IDF。
     * @param totalDocs    索引中的文档总数。用于计算 IDF。
     * @param avgDocLength 文档的平均长度。用于 BM25 公式中的长度归一化（惩罚长文档）。
     * @param request      原始搜索请求对象（包含过滤条件、TopK 等参数，可用于上下文过滤）。
     * @return Map<String, BM25Score> 文档ID 到 分数对象 的映射
     */
    private Map<String, BM25Score> calculateScores(
            List<String> queryTokens,
            Map<String, Integer> docFreqMap,
            int totalDocs,
            double avgDocLength,
            Bm25SearchRequest request) {

        Map<String, BM25Score> scores = new HashMap<>();

        // 构建查询条件
        LambdaQueryWrapper<Bm25TermFreq> queryWrapper =
                new LambdaQueryWrapper<Bm25TermFreq>()
                        .in(Bm25TermFreq::getTerm, queryTokens);

        // 获取所有相关的词频记录
        List<Bm25TermFreq> termFreqs = termFreqMapper.selectList(queryWrapper);

        // 按文档分组
        Map<String, Map<String, Integer>> docTermFreqs = new HashMap<>();
        Set<String> docIds = new HashSet<>();

        for (Bm25TermFreq tf : termFreqs) {
            docIds.add(tf.getDocId());
            docTermFreqs
                    .computeIfAbsent(tf.getDocId(), k -> new HashMap<>())
                    .put(tf.getTerm(), tf.getFrequency());
        }

        // 【性能优化】批量查询 bm25_index 获取文档长度，替代 N+1 单独查询
        Map<String, Integer> docLengthMap = new HashMap<>();
        if (!docIds.isEmpty()) {
            List<Bm25Index> indexList = indexMapper.selectList(
                    new LambdaQueryWrapper<Bm25Index>()
                            .in(Bm25Index::getInternalId, docIds)
            );
            for (Bm25Index index : indexList) {
                docLengthMap.put(index.getInternalId(), index.getTokenCount());
            }
        }

        // 对每个文档计算 BM25 分数
        for (String docId : docIds) {
            Map<String, Integer> termFreqsInDoc = docTermFreqs.get(docId);

            // 从 Map 中获取文档长度（已批量查询）
            Integer docLengthObj = docLengthMap.get(docId);
            if (docLengthObj == null) continue;
            int docLength = docLengthObj;

            Set<String> matchedTerms = new HashSet<>();

            double totalScore = 0.0;
            for (String term : queryTokens) {
                Integer docFreq = docFreqMap.get(term);
                if (docFreq == null || docFreq == 0) continue;

                // 计算 IDF
                double idf = calculateIDF(totalDocs, docFreq);

                // 获取词频
                Integer freq = termFreqsInDoc.get(term);
                if (freq == null) continue;

                // 计算该词对分数的贡献
                double contribution = calculateBM25(freq, docLength, avgDocLength, idf);
                totalScore += contribution;

                matchedTerms.add(term);
            }

            if (totalScore > 0) {
                scores.put(docId, new BM25Score(totalScore, matchedTerms));
            }
        }

        return scores;
    }

    /**
     * 计算 BM25 分数（单个词对单个文档的贡献）
     */
    private double calculateBM25(int freq, int docLength, double avgDocLength, double idf) {
        double numerator = freq * (K1 + 1);
        double denominator = freq + K1 * (1 - B + B * docLength / avgDocLength);
        return idf * numerator / denominator;
    }


    /**
     * 计算 IDF
     */
    private double calculateIDF(int totalDocs, int docFreq) {
        return Math.log((totalDocs - docFreq + 0.5) / (docFreq + 0.5) + 1);
    }

    /**
     * 填充 chunk 内容和元数据
     * <p>
     * 【性能优化】使用 MyBatis Mapper 批量查询替代 N+1 向量检索查询
     * <p>
     * 说明：BM25 检索返回的是 chunk（文档片段），不是完整文档
     * 每个 chunk 的 metadata 包含来源文档信息（filename 等）
     */
    private void fillDocumentContent(List<Bm25SearchResult> results) {
        if (results.isEmpty()) return;

        // 收集所有 internal_id（业务标识）
        List<String> internalIds = results.stream()
                .map(Bm25SearchResult::getDocId)
                .distinct()
                .toList();

        // 【关键优化】使用 Mapper 批量查询，替代 N+1 向量检索
        // 用 internal_id 作为 key，因为 result.getDocId() 是 internal_id
        Map<String, VectorStoreDoc> chunkMap = new HashMap<>();
        try {
            List<VectorStoreDoc> chunks = vectorStoreDocMapper.selectByIds(internalIds);
            for (VectorStoreDoc chunk : chunks) {
                // 从 metadata 中提取 internal_id 作为 key
                String internalId = extractInternalId(chunk.getMetadata());
                if (internalId != null) {
                    chunkMap.put(internalId, chunk);
                }
            }

        } catch (Exception e) {
            log.warn("【BM25】批量查询失败，降级为逐个查询", e);
            fillDocumentContentFallback(results);
            return;
        }

        // 填充结果
        for (Bm25SearchResult result : results) {
            VectorStoreDoc chunk = chunkMap.get(result.getDocId());
            if (chunk != null && chunk.getContent() != null) {
                result.setContent(chunk.getContent());
                result.setMetadata(parseMetadata(chunk.getMetadata()));
            } else {
                result.setContent("[chunk 内容未找到]");
                result.setMetadata(new HashMap<>());
            }
        }
    }

    /**
     * 从 JSONB metadata 中提取 internal_id
     */
    private String extractInternalId(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> metadata = mapper.readValue(metadataJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return (String) metadata.get("internal_id");
        } catch (Exception e) {
            log.debug("解析 metadata 失败: {}", metadataJson, e);
            return null;
        }
    }

    /**
     * 降级处理：逐个查询向量库（原来的方式，很慢）
     */
    private void fillDocumentContentFallback(List<Bm25SearchResult> results) {
        log.warn("降级处理了！！！");
        for (Bm25SearchResult result : results) {
            try {
                List<Document> docs = pgVectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query("")
                                .topK(1)
                                .filterExpression("internal_id == '" + result.getDocId() + "'")
                                .similarityThreshold(0.0)
                                .build()
                );

                if (!docs.isEmpty()) {
                    Document doc = docs.get(0);
                    result.setContent(doc.getText());
                    result.setMetadata(doc.getMetadata());
                } else {
                    result.setContent("[文档内容未找到]");
                    result.setMetadata(new HashMap<>());
                }
            } catch (Exception e) {
                log.warn("【BM25】获取文档内容失败: internal_id={}", result.getDocId(), e);
                result.setContent("[获取失败]");
                result.setMetadata(new HashMap<>());
            }
        }
    }

    /**
     * 解析 JSONB metadata 字段
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(metadataJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("解析 metadata 失败: {}", metadataJson, e);
            return new HashMap<>();
        }
    }


    /**
     * BM25 分数记录
     */
    private record BM25Score(
            double score,
            Set<String> matchedTerms
    ) {}
}
