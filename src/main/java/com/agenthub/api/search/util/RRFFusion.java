package com.agenthub.api.search.util;


import com.agenthub.api.search.dto.result.Bm25SearchResult;
import com.agenthub.api.search.dto.result.HybridSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 融合工具
 *
 * 用于合并向量检索和BM25检索的结果
 */
@Slf4j
@Component
public class RRFFusion {

    /**
     * 默认 RRF 参数 k
     */
    private static final int DEFAULT_K = 60;

    /**
     * 融合向量检索结果和BM25检索结果
     *
     * @param vectorResults  向量检索结果
     * @param bm25Results    BM25检索结果
     * @param k              RRF参数
     * @param topK           返回结果数量
     * @return 融合后的结果
     */
    public List<HybridSearchResult> fuse(
            List<Document> vectorResults,
            List<Bm25SearchResult> bm25Results,
            int k,
            int topK) {

        // 构建文档ID到结果的映射
        Map<String, HybridSearchResult> fusedMap = new HashMap<>();

        // 处理向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            Document doc = vectorResults.get(i);
            String docId = doc.getId();

            HybridSearchResult result = new HybridSearchResult();
            result.setDocId(docId);
            result.setContent(doc.getText());
            result.setMetadata(doc.getMetadata());
            result.setVectorRank(i + 1);  // 排名从1开始

            // 计算RRF贡献
            double rrfScore = 1.0 / (k + i + 1);
            result.setScore(rrfScore);
            result.setSource(HybridSearchResult.ResultSource.VECTOR);

            fusedMap.put(docId, result);
        }

        // 处理BM25检索结果
        for (int i = 0; i < bm25Results.size(); i++) {
            Bm25SearchResult bm25Result = bm25Results.get(i);
            String docId = bm25Result.getDocId();

            HybridSearchResult existing = fusedMap.get(docId);

            if (existing != null) {
                // 文档在两个结果中都存在，累加RRF分数
                double bm25Rrf = 1.0 / (k + i + 1);
                existing.setScore(existing.getScore() + bm25Rrf);
                existing.setBm25Score(bm25Result.getScore());
                existing.setBm25Rank(i + 1);
                existing.setMatchedTerms(bm25Result.getMatchedTerms());
                existing.setSource(HybridSearchResult.ResultSource.BOTH);
            } else {
                // 文档只在BM25结果中
                HybridSearchResult result = new HybridSearchResult();
                result.setDocId(docId);
                result.setContent(bm25Result.getContent());
                result.setMetadata(bm25Result.getMetadata());
                result.setBm25Score(bm25Result.getScore());
                result.setBm25Rank(i + 1);
                result.setMatchedTerms(bm25Result.getMatchedTerms());

                double rrfScore = 1.0 / (k + i + 1);
                result.setScore(rrfScore);
                result.setSource(HybridSearchResult.ResultSource.BM25);

                fusedMap.put(docId, result);
            }
        }

        // 按融合分数排序
        return fusedMap.values().stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }

    /**
     * 仅使用 RRF 分数融合（简化版本）
     *
     * @param vectorDocIds   向量检索的文档ID列表（按排名排序）
     * @param bm25DocIds     BM25检索的文档ID列表（按排名排序）
     * @param k              RRF参数
     * @return 文档ID到融合分数的映射
     */
    public Map<String, Double> fuseSimple(
            List<String> vectorDocIds,
            List<String> bm25DocIds,
            int k) {

        Map<String, Double> scores = new HashMap<>();

        // 向量检索贡献
        for (int i = 0; i < vectorDocIds.size(); i++) {
            String docId = vectorDocIds.get(i);
            double rrf = 1.0 / (k + i + 1);
            scores.put(docId, scores.getOrDefault(docId, 0.0) + rrf);
        }

        // BM25检索贡献
        for (int i = 0; i < bm25DocIds.size(); i++) {
            String docId = bm25DocIds.get(i);
            double rrf = 1.0 / (k + i + 1);
            scores.put(docId, scores.getOrDefault(docId, 0.0) + rrf);
        }

        return scores;
    }

    /**
     * 加权融合（需要预先标准化分数）
     *
     * @param vectorScores 向量分数映射（文档ID -> 标准化后的分数）
     * @param bm25Scores    BM25分数映射（文档ID -> 标准化后的分数）
     * @param bm25Weight    BM25权重（0-1）
     * @return 融合后的分数映射
     */
    public Map<String, Double> weightedFuse(
            Map<String, Double> vectorScores,
            Map<String, Double> bm25Scores,
            double bm25Weight) {

        double vectorWeight = 1.0 - bm25Weight;
        Map<String, Double> fused = new HashMap<>();

        // 向量分数贡献
        vectorScores.forEach((docId, score) ->
                fused.put(docId, score * vectorWeight)
        );

        // BM25分数贡献
        bm25Scores.forEach((docId, score) ->
                fused.merge(docId, score * bm25Weight, Double::sum)
        );

        return fused;
    }

    /**
     * Min-Max 标准化
     *
     * @param scores 原始分数
     * @return 标准化到 [0, 1] 的分数
     */
    public Map<String, Double> normalize(Map<String, Double> scores) {
        if (scores.isEmpty()) {
            return scores;
        }

        double min = Collections.min(scores.values());
        double max = Collections.max(scores.values());

        if (max == min) {
            // 所有分数相同，返回全1
            return scores.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> 1.0
                    ));
        }

        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            double value = (entry.getValue() - min) / (max - min);
            normalized.put(entry.getKey(), value);
        }

        return normalized;
    }
}
