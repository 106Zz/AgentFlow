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
   * 批量删除多个知识库的BM25索引（在单个事务中完成）
   *
   * @param knowledgeIds 知识库ID列表
   */
  void deleteByKnowledgeIds(List<Long> knowledgeIds);

  /**
   * 获取全局统计
   *
   * @return [总文档数, 平均文档长度]
   */
  long[] getGlobalStats();

  /**
   * 异步重建文档频率表和更新全局统计
   * 在删除操作完成后调用，失败不影响删除结果
   */
  void asyncRebuildDocFreqAndStats();

  /**
   * 批量索引文档（同步，在单个事务内完成）
   *
   * @param docs 文档列表
   * @param knowledgeId 知识库ID
   * @param userId 用户ID
   */
  void batchIndexDocuments(List<Document> docs, Long knowledgeId, Long userId);
}
