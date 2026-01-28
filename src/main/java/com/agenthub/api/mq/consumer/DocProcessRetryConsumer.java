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

      // 更新状态为已完成
      knowledge.setVectorStatus("2");
      knowledge.setVectorCount(vectorCount);
      knowledgeBaseService.updateById(knowledge);
      statusNotifier.notifyCompleted(knowledge.getUserId(), knowledge.getId(), vectorCount);

      // 成功后 ACK
      channel.basicAck(deliveryTag, false);

    } catch (Exception e) {
      log.error("【RabbitMQ】文档处理重试失败: {}", e.getMessage(), e);

      try {
        DocProcessRetryMessage nextMessage = message.nextRetry(e.getMessage(), classifyError(e));

        if (nextMessage.needRetry()) {
          long delayMillis = calculateDelayMillis(nextMessage.getRetryCount());
          producer.sendRetryMessage(nextMessage, delayMillis);
        } else {
          producer.sendToDeadLetterQueue(nextMessage);
          // 更新状态为失败
          knowledge.setVectorStatus("3");
          knowledge.setRemark("处理失败（已重试 " + message.getRetryCount() + " 次）: " + e.getMessage());
          knowledgeBaseService.updateById(knowledge);
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
}
