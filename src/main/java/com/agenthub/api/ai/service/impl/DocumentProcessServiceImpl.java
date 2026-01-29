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
   * v4.3 - 提升到10以支持更多并发处理
   */
  private final Semaphore dashScopeSemaphore = new Semaphore(10);

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
      updateStatusWithRetry(knowledge, "2", null);
      statusNotifier.notifyProcessing(knowledge.getUserId(), knowledgeId);

      // 执行文档处理（调用公共方法）
      int vectorCount = processDocumentCore(knowledge);

      // 更新状态为已完成 + 推送（带重试）
      updateStatusWithRetry(knowledge, "3", vectorCount);
      statusNotifier.notifyCompleted(knowledge.getUserId(), knowledgeId, vectorCount);

      log.info("【处理完成】知识库 ID: {}, 向量数量: {}", knowledgeId, vectorCount);

    } catch (Exception e) {
      log.error("【处理失败】知识库 ID: {}", knowledgeId, e);

      // 更新状态为失败 + 推送（带重试）
      updateStatusWithRetry(knowledge, "4", null);

      // 单独更新 remark（避免影响状态更新）
      try {
        knowledge.setRemark("处理失败（已重试 0 次）: " + e.getMessage());
        knowledgeBaseService.updateById(knowledge);
      } catch (Exception remarkEx) {
        log.warn("【remark更新失败】知识库 ID: {}", knowledgeId, remarkEx);
      }

      statusNotifier.notifyFailed(knowledge.getUserId(), knowledgeId, e.getMessage());

      // 发送 MQ 重试消息（延迟 30 秒）
      sendRetryMessage(knowledge, e, 30 * 1000L);
    }
  }

  /**
   * 批量处理
   *
   * @param knowledgeIds 知识库ID数组
   */
  public void batchProcessKnowledge(Long[] knowledgeIds) {
    log.info("【批量处理】开始处理 {} 个知识库", knowledgeIds.length);

    for (Long knowledgeId : knowledgeIds) {
      processKnowledgeAsync(knowledgeId);
    }
  }

  /**
   * 核心文档处理逻辑（提取为公共方法，供 MQ Consumer 调用）
   * v4.3 - 优先使用本地临时文件，节省 OSS 流量
   *
   * @param knowledge 知识库信息
   * @return 向量数量
   * @throws Exception 处理异常
   */
  public int processDocumentCore(KnowledgeBase knowledge) throws Exception {
    String filePath = knowledge.getFilePath();
    if (filePath == null || filePath.isEmpty()) {
      throw new ServiceException("文件路径为空，无法处理");
    }

    byte[] fileBytes;

    // v4.3 优先级：本地临时文件 > OSS（节省流量）
    // 1. 如果 filePath 是本地路径，直接使用
    if (!filePath.startsWith("knowledge/")) {
      log.info("【文档处理】使用本地临时文件: {}", filePath);
      File localFile = new File(filePath);

      if (!localFile.exists()) {
        throw new ServiceException("临时文件不存在: " + filePath + "，请重新上传");
      }

      fileBytes = new FileInputStream(localFile).readAllBytes();
    } else {
      // 2. filePath 是 OSS 路径，先检查临时文件夹是否还有该文件
      File tempFile = findTempFile(knowledge.getFileName(), knowledge.getFileSize());
      if (tempFile != null && tempFile.exists()) {
        log.info("【文档处理】发现本地临时文件仍存在，优先使用（节省流量）: {}", tempFile.getPath());
        fileBytes = new FileInputStream(tempFile).readAllBytes();
      } else {
        // 3. 临时文件不存在，从 OSS 读取
        log.info("【文档处理】本地临时文件不存在，从OSS读取: {}", filePath);
        fileBytes = ossUtils.readFileAsBytes(filePath);
      }
    }

    // 构建额外元数据
    Map<String, Object> extraMetadata = new HashMap<>();
    extraMetadata.put("category", knowledge.getCategory());
    extraMetadata.put("tags", knowledge.getTags());
    extraMetadata.put("title", knowledge.getTitle());
    extraMetadata.put("file_type", knowledge.getFileType());

    // 向量化处理（使用信号量控制并发）
    return processWithSemaphore(fileBytes, knowledge.getFileName(), knowledge.getFileSize(),
            knowledge.getId(), knowledge.getUserId(), knowledge.getIsPublic(), extraMetadata);
  }

  /**
   * v4.3 - 在临时文件夹中查找匹配的文件
   * 根据原始文件名查找，避免重复从 OSS 下载
   *
   * @param originalFileName 原始文件名
   * @param fileSize 文件大小（用于精确匹配）
   * @return 找到的临时文件，未找到返回 null
   */
  private File findTempFile(String originalFileName, Long fileSize) {
    try {
      // 获取临时目录
      String tempDir = System.getProperty("user.dir") + File.separator +
                      "src" + File.separator + "main" + File.separator +
                      "resources" + File.separator + "temp" + File.separator;
      File tempDirFile = new File(tempDir);
      if (!tempDirFile.exists() || !tempDirFile.isDirectory()) {
        return null;
      }

      // 提取文件扩展名
      String ext = "";
      if (originalFileName != null && originalFileName.contains(".")) {
        ext = originalFileName.substring(originalFileName.lastIndexOf("."));
      }

      // 遍历临时目录，查找匹配的文件
      File[] files = tempDirFile.listFiles();
      if (files == null) {
        return null;
      }

      File bestMatch = null;
      for (File file : files) {
        if (file.isFile() && file.getName().endsWith(ext)) {
          // 优先匹配文件大小相同的文件
          if (fileSize != null && file.length() == fileSize) {
            log.debug("【临时文件查找】找到精确匹配的临时文件: {} (大小: {})", file.getName(), file.length());
            return file;
          }
          // 如果没有精确匹配，记住这个候选文件
          if (bestMatch == null) {
            bestMatch = file;
          }
        }
      }

      // 返回最佳匹配（如果有）
      if (bestMatch != null) {
        log.debug("【临时文件查找】找到候选临时文件: {} (大小: {})", bestMatch.getName(), bestMatch.length());
      }
      return bestMatch;
    } catch (Exception e) {
      log.warn("【临时文件查找】查找临时文件失败: {}", e.getMessage());
      return null;
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
   * 更新知识库状态（带重试机制）
   * 确保状态更新不会因为临时网络问题而失败
   */
  private void updateStatusWithRetry(KnowledgeBase knowledge, String status, Integer vectorCount) {
    int maxRetries = 3;
    int attempt = 0;
    boolean success = false;

    while (attempt < maxRetries && !success) {
      try {
        attempt++;
        knowledge.setVectorStatus(status);
        if (vectorCount != null) {
          knowledge.setVectorCount(vectorCount);
        }

        knowledgeBaseService.updateById(knowledge);
        success = true;
        log.debug("【状态更新成功】知识库 ID: {}, 状态: {}, 尝试次数: {}",
            knowledge.getId(), status, attempt);

      } catch (Exception e) {
        log.warn("【状态更新失败】知识库 ID: {}, 状态: {}, 尝试: {}/{}, 错误: {}",
            knowledge.getId(), status, attempt, maxRetries, e.getMessage());

        if (attempt >= maxRetries) {
          log.error("【状态更新最终失败】知识库 ID: {}, 状态: {}, 已重试 {} 次",
              knowledge.getId(), status, maxRetries);
          // 最后一次尝试：使用新的 session 绕续重试
          try {
            // 重新获取最新的 knowledge 对象
            KnowledgeBase latest = knowledgeBaseService.getById(knowledge.getId());
            if (latest != null) {
              latest.setVectorStatus(status);
              if (vectorCount != null) {
                latest.setVectorCount(vectorCount);
              }
              knowledgeBaseService.updateById(latest);
              log.info("【状态更新最终成功】知识库 ID: {}, 状态: {} (使用新 session)",
                  knowledge.getId(), status);
              success = true;
            }
          } catch (Exception finalEx) {
            log.error("【状态更新彻底失败】知识库 ID: {}, 无法更新状态", knowledge.getId(), finalEx);
          }
        } else {
          // 等待后重试
          try {
            Thread.sleep(100 * attempt);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }
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
