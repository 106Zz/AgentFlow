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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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
  public void indexDocument(String vectorId, String content, Long knowledgeId, Long userId) {
    log.debug("【BM25索引】开始索引文档: {}", vectorId);

    // 步骤1：分词
    List<String> tokens = tokenizer.tokenize(content);
    if (tokens.isEmpty()) {
      log.warn("【BM25索引】分词结果为空: {}", vectorId);
      return;
    }

    // 将tokens转换为JSON字符串存储
    String tokensJson = String.join(",", tokens);

    // 步骤2：存储或更新索引
    Bm25Index existingIndex = indexMapper.selectOne(
            new LambdaQueryWrapper<Bm25Index>()
                    .eq(Bm25Index::getVectorId, vectorId)
    );

    if (existingIndex == null) {
      // 新增
      Bm25Index index = new Bm25Index();
      index.setVectorId(vectorId);
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
    updateTermFreq(vectorId, tokens);

    // 步骤4：更新文档频率
    updateDocFreq(tokens);

    // 步骤5：更新全局统计
    updateGlobalStats();

    log.debug("【BM25索引】索引完成: {}, 词数: {}", vectorId, tokens.size());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteDocument(String vectorId) {
    log.info("【BM25索引】删除索引: {}", vectorId);

    // 获取该文档的所有词（用于更新DF）
    List<Bm25TermFreq> termFreqs = termFreqMapper.selectList(
            new LambdaQueryWrapper<Bm25TermFreq>()
                    .eq(Bm25TermFreq::getDocId, vectorId)
    );
    Set<String> terms = new HashSet<>();
    for (Bm25TermFreq tf : termFreqs) {
      terms.add(tf.getTerm());
    }

    // 删除词频记录
    termFreqMapper.delete(
            new LambdaQueryWrapper<Bm25TermFreq>()
                    .eq(Bm25TermFreq::getDocId, vectorId)
    );

    // 删除索引记录
    indexMapper.delete(
            new LambdaQueryWrapper<Bm25Index>()
                    .eq(Bm25Index::getVectorId, vectorId)
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
    for (Bm25Index index : indexes) {
      deleteDocument(index.getVectorId());
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
    // 获取唯一词
    Set<String> uniqueTerms = new HashSet<>(tokens);

    for (String term : uniqueTerms) {
      // 计算包含这个词的文档数量
      Long count = termFreqMapper.selectCount(
              new LambdaQueryWrapper<Bm25TermFreq>()
                      .eq(Bm25TermFreq::getTerm, term)
      );

      if (count > 0) {
        // 先尝试更新（记录已存在）
        Bm25DocFreq docFreq = new Bm25DocFreq();
        docFreq.setTerm(term);
        docFreq.setDocCount(count.intValue());

        int updated = docFreqMapper.updateById(docFreq);

        // 如果更新失败（记录不存在），则插入新记录
        if (updated == 0) {
          try {
            docFreqMapper.insert(docFreq);
            log.debug("【BM25索引】新增文档频率: term={}, count={}", term, count);
          } catch (Exception e) {
            log.warn("【BM25索引】插入文档频率失败: term={}", term, e);
          }
        }
      }
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
      stats = new Bm25Stats();
      stats.setKey(key);
    }
    stats.setValue(value);
    statsMapper.updateById(stats);
  }
}
