package com.agenthub.api.ai.service.impl;

import com.agenthub.api.ai.utils.VectorStoreHelper;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.common.utils.OssUtils;
import com.agenthub.api.framework.sse.KnowledgeStatusNotifier;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import com.agenthub.api.mq.domain.DocProcessRetryMessage;
import com.agenthub.api.mq.producer.DocProcessRetryProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 文档处理服务实现
 * 负责从OSS下载文件、解析、向量化
 * <p>
 * v4.1 新增功能：
 * - SSE 实时状态推送
 * - MQ 延迟重试机制
 * - 并发控制（防止 DashScope 限流）
 * </p>
 */
@Service
@Slf4j
public class DocumentProcessServiceImpl {

  private VectorStoreHelper vectorStoreHelper;
  private OssUtils ossUtils;
  private final KnowledgeStatusNotifier statusNotifier;
  private final DocProcessRetryProducer retryProducer;

  @Autowired
  @Lazy
  private IKnowledgeBaseService knowledgeBaseService;

  /**
   * DashScope 并发控制信号量
   * 限制同时进行的请求数量，防止触发限流
   */
  private final Semaphore dashScopeSemaphore = new Semaphore(3);

  public DocumentProcessServiceImpl(
          VectorStoreHelper vectorStoreHelper,
          OssUtils ossUtils,
          KnowledgeStatusNotifier statusNotifier,
          DocProcessRetryProducer retryProducer) {
    this.vectorStoreHelper = vectorStoreHelper;
    this.ossUtils = ossUtils;
    this.statusNotifier = statusNotifier;
    this.retryProducer = retryProducer;
  }

  /**
   * 异步处理知识库文档
   * 1. 从OSS下载文件
   * 2. 解析文档
   * 3. 向量化存储
   *
   * @param knowledgeId 知识库ID
   */
  @Async("fileProcessExecutor")
  public void processKnowledgeAsync(Long knowledgeId) {
    log.info("【异步任务】开始处理知识库，ID: {}", knowledgeId);

    // 获取知识库信息
    KnowledgeBase knowledge = knowledgeBaseService.getById(knowledgeId);
    if (knowledge == null) {
      log.error("【处理失败】知识库不存在，ID: {}", knowledgeId);
      return;
    }

    try {
      // 更新状态为处理中 + 推送
      updateStatus(knowledge, "1", null);
      statusNotifier.notifyProcessing(knowledge.getUserId(), knowledgeId);

      // 执行文档处理（调用公共方法）
      int vectorCount = processDocumentCore(knowledge);

      // 更新状态为已完成 + 推送
      updateStatus(knowledge, "2", vectorCount);
      statusNotifier.notifyCompleted(knowledge.getUserId(), knowledgeId, vectorCount);

      log.info("【处理完成】知识库 ID: {}, 向量数量: {}", knowledgeId, vectorCount);

    } catch (Exception e) {
      log.error("【处理失败】知识库 ID: {}", knowledgeId, e);

      // 更新状态为失败 + 推送
      updateStatus(knowledge, "3", null);
      knowledge.setRemark("处理失败: " + e.getMessage());
      knowledgeBaseService.updateById(knowledge);
      statusNotifier.notifyFailed(knowledge.getUserId(), knowledgeId, e.getMessage());

      // 发送 MQ 重试消息（延迟 30 秒）
      sendRetryMessage(knowledge, e, 30 * 1000L);
    }
  }

  /**
   * 批量异步处理
   *
   * @param knowledgeIds 知识库ID数组
   */
  @Async("fileProcessExecutor")
  public void batchProcessKnowledge(Long[] knowledgeIds) {
    log.info("【批量处理】开始处理 {} 个知识库", knowledgeIds.length);

    for (Long knowledgeId : knowledgeIds) {
      processKnowledgeAsync(knowledgeId);
    }
  }

  /**
   * 核心文档处理逻辑（提取为公共方法，供 MQ Consumer 调用）
   *
   * @param knowledge 知识库信息
   * @return 向量数量
   * @throws Exception 处理异常
   */
  public int processDocumentCore(KnowledgeBase knowledge) throws Exception {
    // 从OSS下载文件到本地临时目录
    String localPath = ossUtils.downloadToTemp(knowledge.getFilePath());
    File localFile = new File(localPath);

    try {
      // 读取文件
      byte[] fileBytes = new FileInputStream(localFile).readAllBytes();

      // 构建额外元数据
      Map<String, Object> extraMetadata = new HashMap<>();
      extraMetadata.put("category", knowledge.getCategory());
      extraMetadata.put("tags", knowledge.getTags());
      extraMetadata.put("title", knowledge.getTitle());
      extraMetadata.put("file_type", knowledge.getFileType());

      // 向量化处理（使用信号量控制并发）
      return processWithSemaphore(fileBytes, knowledge.getFileName(), knowledge.getFileSize(),
              knowledge.getId(), knowledge.getUserId(), knowledge.getIsPublic(), extraMetadata);

    } finally {
      // 删除临时文件
      if (localFile.exists()) {
        localFile.delete();
        log.debug("临时文件已删除: {}", localPath);
      }
    }
  }

  /**
   * 使用信号量控制并发的向量化处理
   * 防止 DashScope 限流
   */
  private int processWithSemaphore(byte[] fileBytes, String fileName, Long fileSize,
                                   Long knowledgeId, Long userId, String isPublic,
                                   Map<String, Object> extraMetadata) throws Exception {
    boolean acquired = false;
    try {
      // 尝试获取信号量，最多等待 5 分钟
      acquired = dashScopeSemaphore.tryAcquire(5, TimeUnit.MINUTES);

      if (!acquired) {
        throw new ServiceException("系统繁忙，请稍后重试");
      }

      log.debug("【并发控制】获取信号量成功，当前可用: {}", dashScopeSemaphore.availablePermits());

      // 执行向量化处理
      return vectorStoreHelper.processAndStore(fileBytes, fileName, fileSize,
              knowledgeId, userId, isPublic, extraMetadata);

    } finally {
      if (acquired) {
        dashScopeSemaphore.release();
        log.debug("【并发控制】释放信号量，当前可用: {}", dashScopeSemaphore.availablePermits());
      }
    }
  }

  /**
   * 发送 MQ 重试消息
   */
  private void sendRetryMessage(KnowledgeBase knowledge, Exception e, long delayMillis) {
    String errorType = classifyError(e);

    DocProcessRetryMessage message = DocProcessRetryMessage.builder()
            .knowledgeId(knowledge.getId())
            .userId(knowledge.getUserId())
            .fileName(knowledge.getFileName())
            .retryCount(0)
            .errorMessage(e.getMessage())
            .errorType(errorType)
            .timestamp(System.currentTimeMillis())
            .build();

    retryProducer.sendRetryMessage(message, delayMillis);
    log.info("【MQ重试】已发送重试消息，知识库ID: {}, 错误类型: {}", knowledge.getId(), errorType);
  }

  /**
   * 分类错误类型
   */
  private String classifyError(Exception e) {
    String message = e.getMessage();
    if (message == null) {
      return "OTHER";
    }

    String lowerMsg = message.toLowerCase();
    if (lowerMsg.contains("rate limit") || lowerMsg.contains("429")) {
      return "RATE_LIMIT";
    } else if (lowerMsg.contains("timeout")) {
      return "TIMEOUT";
    } else if (lowerMsg.contains("connection") || lowerMsg.contains("network")) {
      return "NETWORK";
    } else if (lowerMsg.contains("database") || lowerMsg.contains("sql")) {
      return "DATA_BASE";
    }
    return "OTHER";
  }

  /**
   * 更新知识库状态
   */
  private void updateStatus(KnowledgeBase knowledge, String status, Integer vectorCount) {
    knowledge.setVectorStatus(status);
    if (vectorCount != null) {
      knowledge.setVectorCount(vectorCount);
    }
    knowledgeBaseService.updateById(knowledge);
  }

  /**
   * 异步执行 OSS 清理任务
   */
  @Async("fileProcessExecutor")
  public void executeOssCleanup(Runnable cleanupTask) {
    log.debug("【OSS清理】开始异步清理任务");
    try {
      cleanupTask.run();
    } catch (Exception e) {
      log.error("【OSS清理】异步清理任务执行失败", e);
    }
  }

  /**
   * 获取当前并发控制状态（用于监控）
   */
  public int getSemaphoreAvailablePermits() {
    return dashScopeSemaphore.availablePermits();
  }
}
