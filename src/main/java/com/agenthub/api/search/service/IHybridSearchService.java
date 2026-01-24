package com.agenthub.api.search.service;


import com.agenthub.api.search.dto.req.HybridSearchRequest;
import com.agenthub.api.search.dto.result.HybridSearchResult;

import java.util.List;

/**
 * 混合检索服务接口
 *
 * 融合向量检索和BM25检索的结果
 */
public interface IHybridSearchService {

    /**
     * 混合检索
     *
     * @param request 检索请求
     * @return 融合后的结果列表
     */
    List<HybridSearchResult> hybridSearch(HybridSearchRequest request);

    /**
     * 简化的检索方法
     *
     * @param query       查询文本
     * @param topK        返回结果数量
     * @param knowledgeId 知识库ID（可选）
     * @param userId      用户ID（可选）
     * @param isAdmin     是否管理员
     * @return 检索结果列表
     */
    List<HybridSearchResult> hybridSearch(
            String query,
            int topK,
            Long knowledgeId,
            Long userId,
            boolean isAdmin
    );
}
