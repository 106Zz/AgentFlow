package com.agenthub.api.mq.producer;

import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.Bm25RetryMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * BM25 重建重试消息生产者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Bm25RetryProducer {

  private final RabbitTemplate rabbitTemplate;

  /**
   * 发送延迟重试消息
   *
   * @param message  重试消息
   * @param delayMillis 延迟毫秒数
   */
  public void sendRetryMessage(Bm25RetryMessage message, long delayMillis) {
    try {
      rabbitTemplate.convertAndSend(
              RabbitMQConfig.BM25_DELAYED_EXCHANGE,
              RabbitMQConfig.BM25_RETRY_ROUTING_KEY,
              message,
              msg -> {
                // 延迟插件使用 x-delay header 设置延迟时间
                msg.getMessageProperties().setHeader("x-delay", (int) delayMillis);
                return msg;
              }
      );
      log.info("【RabbitMQ】BM25 重建重试消息已发送，重试次数: {}, 延迟: {}ms",
              message.getRetryCount(), delayMillis);
    } catch (Exception e) {
      log.error("【RabbitMQ】发送 BM25 重试消息失败: {}", e.getMessage(), e);
    }
  }

  /**
   * 发送消息到死信队列
   *
   * @param message 失败消息
   */
  public void sendToDeadLetterQueue(Bm25RetryMessage message) {
    try {
      rabbitTemplate.convertAndSend(
              RabbitMQConfig.BM25_DLQ_EXCHANGE,
              RabbitMQConfig.BM25_DLQ_ROUTING_KEY,
              message
      );
      log.error("【RabbitMQ】BM25 重建已达到最大重试次数，发送到死信队列，重试次数: {}",
              message.getRetryCount());
    } catch (Exception e) {
      log.error("【RabbitMQ】发送死信队列失败: {}", e.getMessage(), e);
    }
  }
}
