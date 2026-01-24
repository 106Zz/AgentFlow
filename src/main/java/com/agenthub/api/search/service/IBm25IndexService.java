package com.agenthub.api.search.service;


import com.agenthub.api.search.domain.Bm25Index;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.ai.document.Document;

import java.util.List;

public interface IBm25IndexService extends IService<Bm25Index> {

  /**
   * 为单个文档切片构建索引
   *
   * @param internalId  内部ID (对应 vector_store.metadata.internal_id)
   * @param content     文档内容
   * @param knowledgeId 知识库ID
   * @param userId      用户ID
   */
  void indexDocument(String internalId, String content, Long knowledgeId, Long userId);

  /**
   * 删除文档的BM25索引
   *
   * @param internalId 内部ID
   */
  void deleteDocument(String internalId);

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

  /**
   * 批量索引文档（同步，在单个事务内完成）
   *
   * @param docs 文档列表
   * @param knowledgeId 知识库ID
   * @param userId 用户ID
   */
  void batchIndexDocuments(List<Document> docs, Long knowledgeId, Long userId);
}
