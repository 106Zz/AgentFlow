package com.agenthub.api.search.dto.req;

import lombok.Data;

/**
 * 混合检索请求
 */
@Data
public class HybridSearchRequest {

    /**
     * 查询文本
     */
    private String query;

    /**
     * 返回结果数量
     */
    private Integer topK = 20;

    /**
     * BM25 检索数量（用于融合）
     */
    private Integer bm25TopK = 20;

    /**
     * 向量检索数量（用于融合）
     */
    private Integer vectorTopK = 30;

    /**
     * RRF 参数 k（默认60）
     */
    private Integer rrfK = 50;

    /**
     * BM25 权重（0-1，默认0.5表示等权融合）
     * 注意：使用 RRF 时这个参数不生效
     * 只有使用加权融合时才生效
     */
    private Double bm25Weight = 0.5;

    /**
     * 融合策略：RRF 或 WEIGHTED
     */
    private FusionStrategy fusionStrategy = FusionStrategy.RRF;

    /**
     * 知识库ID过滤（可选）
     */
    private Long knowledgeId;

    /**
     * 用户ID过滤（可选）
     */
    private Long userId;

    /**
     * 是否是管理员
     */
    private Boolean isAdmin = false;

    /**
     * 融合策略枚举
     */
    public enum FusionStrategy {
        /**
         * RRF 融合（推荐）
         */
        RRF,

        /**
         * 加权融合（需要标准化分数）
         */
        WEIGHTED
    }
}
