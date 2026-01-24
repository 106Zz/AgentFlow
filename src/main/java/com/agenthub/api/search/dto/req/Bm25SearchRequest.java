package com.agenthub.api.search.dto.req;

import lombok.Data;

/**
 * BM25检索请求
 */
@Data
public class Bm25SearchRequest {

    /**
     * 查询文本
     */
    private String query;

    /**
     * 返回结果数量
     */
    private Integer topK = 10;

    /**
     * 最小分数阈值
     */
    private Double minScore = 0.0;

    /**
     * 知识库ID过滤（可选）
     */
    private Long knowledgeId;

    /**
     * 用户ID过滤（可选）
     */
    private Long userId;

    /**
     * 是否是管理员（用于权限判断）
     */
    private Boolean isAdmin = false;
}
