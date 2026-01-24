package com.agenthub.api.search.service;


import com.agenthub.api.search.domain.Bm25Index;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBm25IndexService extends IService<Bm25Index> {

  /**
   * 为单个文档切片构建索引
   *
   * @param vectorId    向量存储ID (vector_store.id)
   * @param content     文档内容
   * @param knowledgeId 知识库ID
   * @param userId      用户ID
   */
  void indexDocument(String vectorId, String content, Long knowledgeId, Long userId);

  /**
   * 删除文档的BM25索引
   *
   * @param vectorId 向量存储ID
   */
  void deleteDocument(String vectorId);

  /**
   * 删除知识库的所有BM25索引
   *
   * @param knowledgeId 知识库ID
   */
  void deleteByKnowledgeId(Long knowledgeId);

  /**
   * 获取全局统计
   *
   * @return [总文档数, 平均文档长度]
   */
  long[] getGlobalStats();
}
