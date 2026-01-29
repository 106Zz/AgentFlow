package com.agenthub.api.mq.consumer;

import com.agenthub.api.ai.service.impl.DocumentProcessServiceImpl;
import com.agenthub.api.framework.sse.KnowledgeStatusNotifier;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.DocProcessRetryMessage;
import com.agenthub.api.mq.producer.DocProcessRetryProducer;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 文档处理重试消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocProcessRetryConsumer {

  private final DocProcessRetryProducer producer;
  private final IKnowledgeBaseService knowledgeBaseService;
  private final KnowledgeStatusNotifier statusNotifier;
  private final DocumentProcessServiceImpl documentProcessService;

  /**
   * 监听文档处理重试队列
   */
  @RabbitListener(queues = RabbitMQConfig.DOC_RETRY_QUEUE,
          containerFactory = "rabbitListenerContainerFactory")
  public void consumeRetryMessage(DocProcessRetryMessage message,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    log.info("【RabbitMQ】收到文档处理重试消息，知识库ID: {}, 重试次数: {}",
            message.getKnowledgeId(), message.getRetryCount());

    KnowledgeBase knowledge = knowledgeBaseService.getById(message.getKnowledgeId());
    if (knowledge == null) {
      log.warn("【RabbitMQ】知识库不存在，跳过重试，ID: {}", message.getKnowledgeId());
      try {
        channel.basicAck(deliveryTag, false);
      } catch (Exception ignored) {
      }
      return;
    }

    try {
      // 推送处理中状态
      statusNotifier.notifyProcessing(knowledge.getUserId(), message.getKnowledgeId());

      // 执行文档处理（复用核心逻辑）
      int vectorCount = documentProcessService.processDocumentCore(knowledge);

      // 更新状态为已完成（带重试）
      updateStatusWithRetry(knowledge, "3", vectorCount);
      statusNotifier.notifyCompleted(knowledge.getUserId(), knowledge.getId(), vectorCount);

      // 成功后 ACK
      channel.basicAck(deliveryTag, false);

      log.info("【RabbitMQ】文档处理重试成功，知识库ID: {}, 向量数量: {}",
          message.getKnowledgeId(), vectorCount);

    } catch (Exception e) {
      log.error("【RabbitMQ】文档处理重试失败: {}", e.getMessage(), e);

      try {
        DocProcessRetryMessage nextMessage = message.nextRetry(e.getMessage(), classifyError(e));

        if (nextMessage.needRetry()) {
          long delayMillis = calculateDelayMillis(nextMessage.getRetryCount());
          producer.sendRetryMessage(nextMessage, delayMillis);
        } else {
          producer.sendToDeadLetterQueue(nextMessage);

          // 更新状态为失败（带重试）
          updateStatusWithRetry(knowledge, "4", null);

          // 单独更新 remark
          try {
            knowledge.setRemark("处理失败（已重试 " + message.getRetryCount() + " 次）: " + e.getMessage());
            knowledgeBaseService.updateById(knowledge);
          } catch (Exception remarkEx) {
            log.warn("【remark更新失败】知识库 ID: {}", knowledge.getId(), remarkEx);
          }

          statusNotifier.notifyFailed(message.getUserId(), message.getKnowledgeId(), e.getMessage());
        }

        channel.basicAck(deliveryTag, false);

      } catch (Exception ex) {
        log.error("【RabbitMQ】处理重试逻辑异常: {}", ex.getMessage());
        try {
          channel.basicNack(deliveryTag, false, true);
        } catch (Exception ignored) {
        }
      }
    }
  }

  /**
   * 监听文档处理死信队列
   */
  @RabbitListener(queues = RabbitMQConfig.DOC_DLQ_QUEUE,
          containerFactory = "rabbitListenerContainerFactory")
  public void consumeDeadLetterMessage(DocProcessRetryMessage message,
                                       Channel channel,
                                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    log.error("【RabbitMQ】死信队列收到文档处理失败消息，知识库ID: {}, 重试次数: {}, 错误: {}",
            message.getKnowledgeId(), message.getRetryCount(), message.getErrorMessage());

    try {
      channel.basicAck(deliveryTag, false);
    } catch (Exception e) {
      log.error("【RabbitMQ】死信消息 ACK 失败: {}", e.getMessage());
    }
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
    } else if (lowerMsg.contains("parse")) {
      return "PARSE_ERROR";
    }
    return "OTHER";
  }

  /**
   * 计算延迟时间（指数退避）
   */
  private long calculateDelayMillis(int retryCount) {
    return switch (retryCount) {
      case 1 -> 30 * 1000L;       // 30秒
      case 2 -> 2 * 60 * 1000L;   // 2分钟
      default -> 5 * 60 * 1000L;   // 5分钟
    };
  }

  /**
   * 更新知识库状态（带重试机制）
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
          // 最后一次尝试：使用新的 session 继续重试
          try {
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
}
