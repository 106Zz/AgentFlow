package com.agenthub.api.mq.consumer;

import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.Bm25RetryMessage;
import com.agenthub.api.mq.producer.Bm25RetryProducer;
import com.agenthub.api.search.domain.Bm25Stats;
import com.agenthub.api.search.mapper.Bm25DocFreqMapper;
import com.agenthub.api.search.mapper.Bm25IndexMapper;
import com.agenthub.api.search.mapper.Bm25StatsMapper;
import com.agenthub.api.search.mapper.Bm25TermFreqMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BM25 重建重试消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Bm25RetryConsumer {

  private final Bm25DocFreqMapper docFreqMapper;
  private final Bm25IndexMapper indexMapper;
  private final Bm25StatsMapper statsMapper;
  private final Bm25TermFreqMapper termFreqMapper;
  private final Bm25RetryProducer producer;

  /**
   * 监听重试队列，执行 BM25 重建
   */
  @RabbitListener(queues = RabbitMQConfig.BM25_RETRY_QUEUE,
          containerFactory = "rabbitListenerContainerFactory")
  public void consumeRetryMessage(Bm25RetryMessage message,
                                  Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    log.info("【RabbitMQ】收到 BM25 重建重试消息，重试次数: {}", message.getRetryCount());

    try {
      // 执行重建操作
      rebuildDocFreq();
      updateGlobalStats();

      log.info("【RabbitMQ】BM25 重建重试成功，重试次数: {}", message.getRetryCount());

      // 手动 ACK
      channel.basicAck(deliveryTag, false);

    } catch (Exception e) {
      log.error("【RabbitMQ】BM25 重建重试失败: {}", e.getMessage(), e);

      try {
        // 判断是否继续重试
        Bm25RetryMessage nextMessage = message.nextRetry(e.getMessage());

        if (nextMessage.needRetry()) {
          // 计算延迟时间（指数退避：1min → 5min → 15min）
          long delayMillis = calculateDelayMillis(nextMessage.getRetryCount());
          producer.sendRetryMessage(nextMessage, delayMillis);
        } else {
          // 达到最大重试次数，发送到死信队列
          producer.sendToDeadLetterQueue(nextMessage);
        }

        // ACK 当前消息（已发送新的重试消息或进入死信队列）
        channel.basicAck(deliveryTag, false);

      } catch (Exception ex) {
        log.error("【RabbitMQ】处理重试逻辑异常，拒绝消息: {}", ex.getMessage(), ex);
        try {
          // 拒绝消息，重新入队
          channel.basicNack(deliveryTag, false, true);
        } catch (Exception ignored) {
        }
      }
    }
  }

  /**
   * 监听死信队列（仅记录日志，人工介入处理）
   */
  @RabbitListener(queues = RabbitMQConfig.BM25_DLQ_QUEUE,
          containerFactory = "rabbitListenerContainerFactory")
  public void consumeDeadLetterMessage(Bm25RetryMessage message,
                                       Channel channel,
                                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    log.error("【RabbitMQ】死信队列收到 BM25 重建失败消息，重试次数: {}, 错误: {}",
            message.getRetryCount(), message.getErrorMessage());

    // 这里可以添加告警逻辑（钉钉、邮件等）

    // ACK 死信消息
    try {
      channel.basicAck(deliveryTag, false);
    } catch (Exception e) {
      log.error("【RabbitMQ】死信消息 ACK 失败: {}", e.getMessage());
    }
  }

  /**
   * 重建文档频率表
   */
  private void rebuildDocFreq() {
    docFreqMapper.rebuildFromTermFreq();
    log.info("【RabbitMQ】文档频率表重建成功");
  }

  /**
   * 更新全局统计
   */
  private void updateGlobalStats() {
    // 总文档数
    Long totalCount = indexMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.agenthub.api.search.domain.Bm25Index>()
                    .eq(com.agenthub.api.search.domain.Bm25Index::getDelFlag, 0)
    );

    // 平均文档长度
    List<com.agenthub.api.search.domain.Bm25Index> allIndexes = indexMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.agenthub.api.search.domain.Bm25Index>()
                    .eq(com.agenthub.api.search.domain.Bm25Index::getDelFlag, 0)
                    .select(com.agenthub.api.search.domain.Bm25Index::getTokenCount)
    );

    double avgLength = allIndexes.stream()
            .mapToInt(com.agenthub.api.search.domain.Bm25Index::getTokenCount)
            .average()
            .orElse(0.0);

    // 更新统计
    Bm25Stats totalDocsStats = statsMapper.selectById("total_docs");
    if (totalDocsStats == null) {
      totalDocsStats = new com.agenthub.api.search.domain.Bm25Stats();
      totalDocsStats.setKey("total_docs");
      totalDocsStats.setValue(totalCount.doubleValue());
      statsMapper.insert(totalDocsStats);
    } else {
      totalDocsStats.setValue(totalCount.doubleValue());
      statsMapper.updateById(totalDocsStats);
    }

    Bm25Stats avgLengthStats = statsMapper.selectById("avg_doc_length");
    if (avgLengthStats == null) {
      avgLengthStats = new com.agenthub.api.search.domain.Bm25Stats();
      avgLengthStats.setKey("avg_doc_length");
      avgLengthStats.setValue(avgLength);
      statsMapper.insert(avgLengthStats);
    } else {
      avgLengthStats.setValue(avgLength);
      statsMapper.updateById(avgLengthStats);
    }

    log.info("【RabbitMQ】全局统计更新成功");
  }

  /**
   * 计算延迟时间（指数退避）
   * 第1次: 1分钟
   * 第2次: 5分钟
   * 第3次: 15分钟
   */
  private long calculateDelayMillis(int retryCount) {
    return switch (retryCount) {
      case 1 -> 60 * 1000L;      // 1分钟
      case 2 -> 5 * 60 * 1000L;  // 5分钟
      default -> 15 * 60 * 1000L; // 15分钟
    };
  }
}
