package com.agenthub.api.search.service.impl;


import com.agenthub.api.search.domain.Bm25DocFreq;
import com.agenthub.api.search.domain.Bm25Index;
import com.agenthub.api.search.domain.Bm25Stats;
import com.agenthub.api.search.domain.Bm25TermFreq;
import com.agenthub.api.search.dto.req.Bm25SearchRequest;
import com.agenthub.api.search.dto.result.Bm25SearchResult;
import com.agenthub.api.search.mapper.Bm25DocFreqMapper;
import com.agenthub.api.search.mapper.Bm25IndexMapper;
import com.agenthub.api.search.mapper.Bm25StatsMapper;
import com.agenthub.api.search.mapper.Bm25TermFreqMapper;
import com.agenthub.api.search.service.IBm25SearchService;
import com.agenthub.api.search.util.ChineseTokenizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
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

    /**
     * BM25 参数
     */
    private static final double K1 = 1.5;  // 词频饱和参数
    private static final double B = 0.75;  // 长度惩罚参数


    @Override
    public List<Bm25SearchResult> search(Bm25SearchRequest request) {
        long startTime = System.currentTimeMillis();

        // 步骤1：对查询进行分词
        List<String> queryTokens = tokenizer.tokenize(request.getQuery());
        if (queryTokens.isEmpty()) {
            log.warn("【BM25检索】查询分词为空: {}", request.getQuery());
            return Collections.emptyList();
        }

        log.debug("【BM25检索】查询分词: {}", queryTokens);

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
        log.info("【BM25检索】查询: '{}', 返回: {}, 耗时: {}ms",
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
     * 计算 BM25 分数
     * <p>
     * 核心算法逻辑：遍历查询词，累加它们在各个文档中的得分。
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

        // 对每个文档计算 BM25 分数
        for (String docId : docIds) {
            Map<String, Integer> termFreqsInDoc = docTermFreqs.get(docId);

            // 获取文档长度
            Bm25Index index = indexMapper.selectOne(
                    new LambdaQueryWrapper<Bm25Index>()
                            .eq(Bm25Index::getVectorId, docId)
            );
            if (index == null) continue;

            int docLength = index.getTokenCount();
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
     * 填充文档内容和元数据
     * 通过 internal_id 从向量库精确查询文档
     */
    private void fillDocumentContent(List<Bm25SearchResult> results) {
        if (results.isEmpty()) return;

        // 批量获取文档内容（按 internal_id 过滤）
        for (Bm25SearchResult result : results) {
            try {
                // 使用 filterExpression 按 internal_id 精确查询
                List<Document> docs = pgVectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query("")  // 空查询，仅靠过滤条件
                                .topK(1)
                                .filterExpression("internal_id == '" + result.getDocId() + "'")
                                .similarityThreshold(0.0)  // 不限制相似度，取过滤后的第一条
                                .build()
                );

                if (!docs.isEmpty()) {
                    Document doc = docs.get(0);
                    result.setContent(doc.getText());
                    result.setMetadata(doc.getMetadata());
                } else {
                    log.warn("【BM25】未找到文档: internal_id={}", result.getDocId());
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
     * BM25 分数记录
     */
    private record BM25Score(
            double score,
            Set<String> matchedTerms
    ) {}
}
