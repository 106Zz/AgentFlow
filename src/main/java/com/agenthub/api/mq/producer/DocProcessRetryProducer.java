package com.agenthub.api.mq.producer;

import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.DocProcessRetryMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 文档处理重试消息生产者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocProcessRetryProducer {

  private final RabbitTemplate rabbitTemplate;

  /**
   * 发送延迟重试消息
   *
   * @param message     重试消息
   * @param delayMillis 延迟毫秒数
   */
  public void sendRetryMessage(DocProcessRetryMessage message, long delayMillis) {
    try {
      rabbitTemplate.convertAndSend(
              RabbitMQConfig.DOC_DELAYED_EXCHANGE,
              RabbitMQConfig.DOC_RETRY_ROUTING_KEY,
              message,
              msg -> {
                msg.getMessageProperties().setHeader("x-delay", (int) delayMillis);
                return msg;
              }
      );
      log.info("【RabbitMQ】文档处理重试消息已发送，知识库ID: {}, 重试次数: {}, 延迟: {}ms",
              message.getKnowledgeId(), message.getRetryCount(), delayMillis);
    } catch (Exception e) {
      log.error("【RabbitMQ】发送文档处理重试消息失败: {}", e.getMessage(), e);
    }
  }

  /**
   * 发送消息到死信队列
   *
   * @param message 失败消息
   */
  public void sendToDeadLetterQueue(DocProcessRetryMessage message) {
    try {
      rabbitTemplate.convertAndSend(
              RabbitMQConfig.DOC_DLQ_EXCHANGE,
              RabbitMQConfig.DOC_DLQ_ROUTING_KEY,
              message
      );
      log.error("【RabbitMQ】文档处理已达到最大重试次数，发送到死信队列，知识库ID: {}, 重试次数: {}",
              message.getKnowledgeId(), message.getRetryCount());
    } catch (Exception e) {
      log.error("【RabbitMQ】发送文档处理死信队列失败: {}", e.getMessage(), e);
    }
  }
}
