package com.agenthub.api.search.dto.result;


import lombok.Data;

import java.util.Map;

/**
 * BM25检索结果
 */
@Data
public class Bm25SearchResult {

    /**
     * 文档ID（vector_store.id）
     */
    private String docId;

    /**
     * BM25分数
     */
    private Double score;

    /**
     * 文档内容
     */
    private String content;

    /**
     * 元数据（来自 vector_store.metadata）
     */
    private Map<String, Object> metadata;

    /**
     * 匹配到的词项（调试用）
     */
    private java.util.Set<String> matchedTerms;
}
