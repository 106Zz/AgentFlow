package com.agenthub.api.search.service.impl;


import com.agenthub.api.search.domain.Bm25DocFreq;
import com.agenthub.api.search.domain.Bm25Index;
import com.agenthub.api.search.domain.Bm25Stats;
import com.agenthub.api.search.domain.Bm25TermFreq;
import com.agenthub.api.search.mapper.Bm25DocFreqMapper;
import com.agenthub.api.search.mapper.Bm25IndexMapper;
import com.agenthub.api.search.mapper.Bm25StatsMapper;
import com.agenthub.api.search.mapper.Bm25TermFreqMapper;
import com.agenthub.api.search.service.IBm25IndexService;
import com.agenthub.api.search.util.ChineseTokenizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class Bm25IndexServiceImpl extends ServiceImpl<Bm25IndexMapper, Bm25Index> implements IBm25IndexService {

  private final Bm25IndexMapper indexMapper;
  private final Bm25TermFreqMapper termFreqMapper;
  private final Bm25DocFreqMapper docFreqMapper;
  private final Bm25StatsMapper statsMapper;
  private final ChineseTokenizer tokenizer;


  @Override
  @Transactional(rollbackFor = Exception.class)
  public void indexDocument(String internalId, String content, Long knowledgeId, Long userId) {
    log.debug("【BM25索引】开始索引文档: {}", internalId);

    // 步骤1：分词
    List<String> tokens = tokenizer.tokenize(content);
    if (tokens.isEmpty()) {
      log.warn("【BM25索引】分词结果为空: {}", internalId);
      return;
    }

    // 将tokens转换为JSON字符串存储
    String tokensJson = String.join(",", tokens);

    // 步骤2：存储或更新索引
    Bm25Index existingIndex = indexMapper.selectOne(
            new LambdaQueryWrapper<Bm25Index>()
                    .eq(Bm25Index::getInternalId, internalId)
    );

    if (existingIndex == null) {
      // 新增
      Bm25Index index = new Bm25Index();
      index.setInternalId(internalId);
      index.setKnowledgeId(knowledgeId);
      index.setUserId(userId);
      index.setTokens(tokensJson);
      index.setTokenCount(tokens.size());
      index.setDelFlag(0);
      indexMapper.insert(index);
    } else {
      // 更新
      existingIndex.setTokens(tokensJson);
      existingIndex.setTokenCount(tokens.size());
      indexMapper.updateById(existingIndex);
    }

    // 步骤3：更新词频统计
    updateTermFreq(internalId, tokens);

    // 步骤4：更新文档频率
    updateDocFreq(tokens);

    // 步骤5：更新全局统计
    updateGlobalStats();

    log.debug("【BM25索引】索引完成: {}, 词数: {}", internalId, tokens.size());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteDocument(String internalId) {
    log.info("【BM25索引】删除索引: {}", internalId);

    // 获取该文档的所有词（用于更新DF）
    List<Bm25TermFreq> termFreqs = termFreqMapper.selectList(
            new LambdaQueryWrapper<Bm25TermFreq>()
                    .eq(Bm25TermFreq::getDocId, internalId)
    );
    Set<String> terms = new HashSet<>();
    for (Bm25TermFreq tf : termFreqs) {
      terms.add(tf.getTerm());
    }

    // 删除词频记录
    termFreqMapper.delete(
            new LambdaQueryWrapper<Bm25TermFreq>()
                    .eq(Bm25TermFreq::getDocId, internalId)
    );

    // 删除索引记录
    indexMapper.delete(
            new LambdaQueryWrapper<Bm25Index>()
                    .eq(Bm25Index::getInternalId, internalId)
    );

    // 更新受影响词的文档频率
    for (String term : terms) {
      Long newCount = termFreqMapper.selectCount(
              new LambdaQueryWrapper<Bm25TermFreq>()
                      .eq(Bm25TermFreq::getTerm, term)
      );

      if (newCount == 0) {
        docFreqMapper.deleteById(term);
      } else {
        Bm25DocFreq docFreq = new Bm25DocFreq();
        docFreq.setTerm(term);
        docFreq.setDocCount(newCount.intValue());
        docFreqMapper.updateById(docFreq);
      }
    }

    // 更新全局统计
    updateGlobalStats();
  }

  @Override
  public void deleteByKnowledgeId(Long knowledgeId) {
    log.info("【BM25索引】删除知识库索引: {}", knowledgeId);

    List<Bm25Index> indexes = indexMapper.selectList(
            new LambdaQueryWrapper<Bm25Index>()
                    .eq(Bm25Index::getKnowledgeId, knowledgeId)
    );

    if (indexes.isEmpty()) {
      return;
    }

    // 2. 提取所有 internalId
    List<String> internalIds = indexes.stream()
            .map(Bm25Index::getInternalId)
            .collect(Collectors.toList());

    // 3. 批量删除词频记录（一条 SQL）
    termFreqMapper.delete(
            new LambdaQueryWrapper<Bm25TermFreq>()
                    .in(Bm25TermFreq::getDocId, internalIds)
    );

    // 4. 批量删除索引记录（一条 SQL）
    indexMapper.delete(
            new LambdaQueryWrapper<Bm25Index>()
                    .in(Bm25Index::getInternalId, internalIds)
    );

    // 5. 重建文档频率表（一条 SQL 完成 TRUNCATE + INSERT）
    docFreqMapper.rebuildFromTermFreq();

    // 6. 更新全局统计
    updateGlobalStats();

    log.info("【BM25索引】批量删除完成，删除 {} 个文档", internalIds.size());


  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteByKnowledgeIds(List<Long> knowledgeIds) {
    if (knowledgeIds == null || knowledgeIds.isEmpty()) {
      return;
    }

    log.info("【BM25索引】批量删除 {} 个知识库的索引", knowledgeIds.size());

    // 1. 查询所有相关的索引记录
    List<Bm25Index> indexes = indexMapper.selectList(
            new LambdaQueryWrapper<Bm25Index>()
                    .in(Bm25Index::getKnowledgeId, knowledgeIds)
    );

    if (indexes.isEmpty()) {
      log.info("【BM25索引】没有找到需要删除的索引");
      return;
    }

    // 2. 提取所有 internalId
    List<String> internalIds = indexes.stream()
            .map(Bm25Index::getInternalId)
            .collect(Collectors.toList());

    // 3. 批量删除词频记录（一条 SQL）
    int termFreqDeleted = termFreqMapper.delete(
            new LambdaQueryWrapper<Bm25TermFreq>()
                    .in(Bm25TermFreq::getDocId, internalIds)
    );

    // 4. 批量删除索引记录（一条 SQL）
    int indexDeleted = indexMapper.delete(
            new LambdaQueryWrapper<Bm25Index>()
                    .in(Bm25Index::getKnowledgeId, knowledgeIds)
    );

    log.info("【BM25索引】批量删除完成，删除 {} 个索引，{} 条词频", indexDeleted, termFreqDeleted);
  }

  /**
   * 异步重建文档频率表和更新统计
   * 在核心删除操作完成后调用，失败不影响删除结果
   */
  @Async("fileProcessExecutor")
  public void asyncRebuildDocFreqAndStats() {
    try {
      log.info("【BM25索引】开始异步重建文档频率表");
      docFreqMapper.rebuildFromTermFreq();
      log.info("【BM25索引】文档频率表重建成功");
    } catch (Exception e) {
      log.error("【BM25索引】文档频率表重建失败，下次索引时会自动恢复: {}", e.getMessage(), e);
    }

    try {
      updateGlobalStats();
      log.info("【BM25索引】全局统计更新成功");
    } catch (Exception e) {
      log.error("【BM25索引】全局统计更新失败: {}", e.getMessage(), e);
    }
  }

  @Override
  public long[] getGlobalStats() {

    Bm25Stats totalDocsStats = statsMapper.selectById("total_docs");
    Bm25Stats avgLengthStats = statsMapper.selectById("avg_doc_length");
    long totalDocs = (totalDocsStats != null) ? totalDocsStats.getValue().longValue() : 0;
    long avgLength = (avgLengthStats != null) ? avgLengthStats.getValue().longValue() : 0;

    return new long[]{totalDocs, avgLength};
  }

  /**
   * 批量索引文档（同步，在单个事务内完成）
   *
   * @param docs 文档列表
   * @param knowledgeId 知识库ID
   * @param userId 用户ID
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public void batchIndexDocuments(List<Document> docs, Long knowledgeId, Long userId) {
    if (docs == null || docs.isEmpty()) {
      return;
    }

    log.debug("【BM25索引】批量索引 {} 个文档", docs.size());

    List<Bm25Index> indexList = new ArrayList<>();
    List<Bm25TermFreq> termFreqList = new ArrayList<>();

    // 收集所有需要批量处理的数据
    for (Document doc : docs) {
      String internalId = (String) doc.getMetadata().get("internal_id");
      String content = doc.getText();

      // 分词
      List<String> tokens = tokenizer.tokenize(content);
      if (tokens.isEmpty()) {
        continue;
      }

      // 1. 准备索引数据
      Bm25Index index = new Bm25Index();
      index.setInternalId(internalId);
      index.setKnowledgeId(knowledgeId);
      index.setUserId(userId);
      index.setTokens(String.join(",", tokens));
      index.setTokenCount(tokens.size());
      index.setDelFlag(0);
      indexList.add(index);

      // 2. 准备词频数据（统计每个词在当前文档中的频率）
      Map<String, Integer> freqMap = new HashMap<>();
      for (String token : tokens) {
        freqMap.merge(token, 1, Integer::sum);
      }

      for (Map.Entry<String, Integer> entry : freqMap.entrySet()) {
        Bm25TermFreq tf = new Bm25TermFreq();
        tf.setTerm(entry.getKey());
        tf.setDocId(internalId);
        tf.setFrequency(entry.getValue());
        termFreqList.add(tf);
      }
    }

    // 批量操作（在同一个事务内）
    if (!indexList.isEmpty()) {
      List<String> internalIds = docs.stream()
              .map(d -> (String) d.getMetadata().get("internal_id"))
              .collect(Collectors.toList());

      // 1. 删除旧词频
      termFreqMapper.delete(
              new LambdaQueryWrapper<Bm25TermFreq>()
                      .in(Bm25TermFreq::getDocId, internalIds)
      );

      // 2. 批量插入/更新索引
      for (Bm25Index index : indexList) {
        Bm25Index existing = indexMapper.selectOne(
                new LambdaQueryWrapper<Bm25Index>()
                        .eq(Bm25Index::getInternalId, index.getInternalId())
        );
        if (existing == null) {
          indexMapper.insert(index);
        } else {
          index.setId(existing.getId());
          indexMapper.updateById(index);
        }
      }

      // 3. 批量插入词频（已有 UPSERT 支持）
      if (!termFreqList.isEmpty()) {
        termFreqMapper.insertBatch(termFreqList);
      }

      // 4. 批量更新文档频率
      Set<String> affectedTerms = termFreqList.stream()
              .map(Bm25TermFreq::getTerm)
              .collect(Collectors.toSet());
      updateDocFreq(new ArrayList<>(affectedTerms));

      // 5. 更新全局统计
      updateGlobalStats();
    }

    log.debug("【BM25索引】批量索引完成");
  }


  /**
   * 更新词频统计
   */
  private void updateTermFreq(String docId, List<String> tokens) {
    // 统计每个词的频率
    Map<String, Integer> freqMap = new HashMap<>();
    for (String token : tokens) {
      freqMap.merge(token, 1, Integer::sum);
    }

    // 删除旧的词频记录
    termFreqMapper.delete(
            new LambdaQueryWrapper<Bm25TermFreq>()
                    .eq(Bm25TermFreq::getDocId, docId)
    );

    // 批量插入新的词频记录
    List<Bm25TermFreq> termFreqList = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : freqMap.entrySet()) {
      Bm25TermFreq termFreq = new Bm25TermFreq();
      termFreq.setTerm(entry.getKey());
      termFreq.setDocId(docId);
      termFreq.setFrequency(entry.getValue());
      termFreqList.add(termFreq);
    }

    if (!termFreqList.isEmpty()) {
      // 使用 MyBatis-Plus 的 saveBatch
      termFreqMapper.insertBatch(termFreqList);
    }
  }

  /**
   * 更新文档频率
   * <p>
   * 修复：原代码使用 updateById 无法插入新记录，导致 bm25_doc_freq 表为空
   * 解决方案：先尝试更新，如果影响行数为0则改用 insert
   */
  private void updateDocFreq(List<String> tokens) {
    if (tokens.isEmpty()) {
      return;
    }

    Set<String> uniqueTerms = new HashSet<>(tokens);
    List<Bm25DocFreq> docFreqList = new ArrayList<>();

    // 批量收集每个词的文档数量
    for (String term : uniqueTerms) {
      // 统计该词在多少个不同文档中出现
      Long docCount = termFreqMapper.selectCount(
              new LambdaQueryWrapper<Bm25TermFreq>()
                      .eq(Bm25TermFreq::getTerm, term)
      );

      Bm25DocFreq df = new Bm25DocFreq();
      df.setTerm(term);
      df.setDocCount(docCount != null ? docCount.intValue() : 0);
      docFreqList.add(df);
    }

    // 批量 UPSERT（一条 SQL 完成）
    if (!docFreqList.isEmpty()) {
      docFreqMapper.insertOrUpdateBatch(docFreqList);
    }
  }

  /**
   * 更新全局统计
   */
  private void updateGlobalStats() {
    // 总文档数
    Long totalCount = indexMapper.selectCount(
            new LambdaQueryWrapper<Bm25Index>()
                    .eq(Bm25Index::getDelFlag, 0)
    );

    // 平均文档长度
    List<Bm25Index> allIndexes = indexMapper.selectList(
            new LambdaQueryWrapper<Bm25Index>()
                    .eq(Bm25Index::getDelFlag, 0)
                    .select(Bm25Index::getTokenCount)
    );

    double avgLength = allIndexes.stream()
            .mapToInt(Bm25Index::getTokenCount)
            .average()
            .orElse(0.0);

    // 更新或插入
    upsertStats("total_docs", totalCount.doubleValue());
    upsertStats("avg_doc_length", avgLength);
  }

  /**
   * 更新或插入统计值
   */
  private void upsertStats(String key, Double value) {
    Bm25Stats stats = statsMapper.selectById(key);
    if (stats == null) {
      // 新记录，直接插入
      stats = new Bm25Stats();
      stats.setKey(key);
      stats.setValue(value);
      statsMapper.insert(stats);
      log.debug("【BM25索引】插入统计: {} = {}", key, value);
    } else {
      // 已存在，更新
      stats.setValue(value);
      statsMapper.updateById(stats);
      log.debug("【BM25索引】更新统计: {} = {}", key, value);
    }
  }
}
