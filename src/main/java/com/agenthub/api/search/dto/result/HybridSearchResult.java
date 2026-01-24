package com.agenthub.api.search.dto.result;


import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * 混合检索结果
 */
@Data
public class HybridSearchResult {
    /**
     * 文档ID（vector_store.id）
     */
    private String docId;

    /**
     * 融合后的分数
     */
    private Double score;

    /**
     * 文档内容
     */
    private String content;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 向量检索分数（用于调试）
     */
    private Double vectorScore;

    /**
     * 向量检索排名（用于调试）
     */
    private Integer vectorRank;

    /**
     * BM25 分数（用于调试）
     */
    private Double bm25Score;

    /**
     * BM25 排名（用于调试）
     */
    private Integer bm25Rank;

    /**
     * 匹配到的词项
     */
    private Set<String> matchedTerms;

    /**
     * 来源标记：VECTOR, BM25, BOTH
     */
    private ResultSource source;

    /**
     * 结果来源枚举
     */
    public enum ResultSource {
        /**
         * 仅向量检索
         */
        VECTOR,

        /**
         * 仅BM25检索
         */
        BM25,

        /**
         * 两者都有
         */
        BOTH
    }
}
