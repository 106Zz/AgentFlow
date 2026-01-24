package com.agenthub.api.search.service;

import com.agenthub.api.search.dto.req.Bm25SearchRequest;
import com.agenthub.api.search.dto.result.Bm25SearchResult;

import java.util.List;

/**
 * BM25检索服务接口
 */
public interface IBm25SearchService {

    /**
     * BM25检索
     *
     * @param request 检索请求
     * @return 检索结果列表
     */
    List<Bm25SearchResult> search(Bm25SearchRequest request);

    /**
     * 简化的检索方法
     *
     * @param query 查询文本
     * @param topK 返回结果数量
     * @param knowledgeId 知识库ID（可选）
     * @param userId 用户ID（可选）
     * @param isAdmin 是否管理员
     * @return 检索结果列表
     */
    List<Bm25SearchResult> search(String query, int topK,
                                  Long knowledgeId, Long userId, boolean isAdmin);
}
