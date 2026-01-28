package com.agenthub.api.mq.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * RabbitMQ 配置类
 * <p>
 * 使用延迟交换机实现 BM25 重建重试机制和文档处理重试机制
 * </p>
 */
@Configuration
public class RabbitMQConfig {

  // ==================== BM25 重建重试 ====================
  public static final String BM25_DELAYED_EXCHANGE = "bm25.delayed.exchange";
  public static final String BM25_DLQ_EXCHANGE = "bm25.dlq.exchange";
  public static final String BM25_RETRY_QUEUE = "bm25.retry.queue";
  public static final String BM25_DLQ_QUEUE = "bm25.dlq.queue";
  public static final String BM25_RETRY_ROUTING_KEY = "bm25.retry";
  public static final String BM25_DLQ_ROUTING_KEY = "bm25.dlq";

  // ==================== 文档处理重试 ====================
  public static final String DOC_DELAYED_EXCHANGE = "doc.delayed.exchange";
  public static final String DOC_DLQ_EXCHANGE = "doc.dlq.exchange";
  public static final String DOC_RETRY_QUEUE = "doc.retry.queue";
  public static final String DOC_DLQ_QUEUE = "doc.dlq.queue";
  public static final String DOC_RETRY_ROUTING_KEY = "doc.retry";
  public static final String DOC_DLQ_ROUTING_KEY = "doc.dlq";

  /**
   * 延迟交换机（x-delayed-message 插件支持）
   */
  @Bean
  public CustomExchange bm25DelayedExchange() {
    return new CustomExchange(
            BM25_DELAYED_EXCHANGE,
            "x-delayed-message",
            true,
            false,
            Map.of("x-delayed-type", "direct")
    );
  }

  /**
   * 死信交换机
   */
  @Bean
  public DirectExchange bm25DlqExchange() {
    return new DirectExchange(BM25_DLQ_EXCHANGE, true, false);
  }

  /**
   * 重试队列（失败后会进入死信队列）
   */
  @Bean
  public Queue bm25RetryQueue() {
    return QueueBuilder.durable(BM25_RETRY_QUEUE)
            .withArgument("x-dead-letter-exchange", BM25_DLQ_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", BM25_DLQ_ROUTING_KEY)
            .build();
  }

  /**
   * 死信队列（最终失败，需要人工介入）
   */
  @Bean
  public Queue bm25DlqQueue() {
    return QueueBuilder.durable(BM25_DLQ_QUEUE).build();
  }

  /**
   * 绑定：延迟交换机 → 重试队列
   * 注意：CustomExchange 需要直接创建 Binding，不能用 BindingBuilder
   */
  @Bean
  public Binding bm25RetryBinding(Queue bm25RetryQueue, CustomExchange bm25DelayedExchange) {
    return new Binding(
            BM25_RETRY_QUEUE,
            Binding.DestinationType.QUEUE,
            BM25_DELAYED_EXCHANGE,
            BM25_RETRY_ROUTING_KEY,
            null
    );
  }

  /**
   * 绑定：死信交换机 → 死信队列
   */
  @Bean
  public Binding bm25DlqBinding() {
    return BindingBuilder.bind(bm25DlqQueue())
            .to(bm25DlqExchange())
            .with(BM25_DLQ_ROUTING_KEY);
  }

  /**
   * 配置 RabbitTemplate，支持发送延迟消息
   */
  @Bean
  public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    // 开启强制回调
    template.setMandatory(true);
    return template;
  }

  /**
   * RabbitAdmin 用于自动声明队列、交换机、绑定关系
   */
  @Bean
  public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
    RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
    rabbitAdmin.setAutoStartup(true);
    return rabbitAdmin;
  }

  // ==================== 文档处理重试配置 ====================

  /**
   * 文档处理延迟交换机
   */
  @Bean
  public CustomExchange docDelayedExchange() {
    return new CustomExchange(
            DOC_DELAYED_EXCHANGE,
            "x-delayed-message",
            true,
            false,
            Map.of("x-delayed-type", "direct")
    );
  }

  /**
   * 文档处理死信交换机
   */
  @Bean
  public DirectExchange docDlqExchange() {
    return new DirectExchange(DOC_DLQ_EXCHANGE, true, false);
  }

  /**
   * 文档处理重试队列
   */
  @Bean
  public Queue docRetryQueue() {
    return QueueBuilder.durable(DOC_RETRY_QUEUE)
            .withArgument("x-dead-letter-exchange", DOC_DLQ_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", DOC_DLQ_ROUTING_KEY)
            .build();
  }

  /**
   * 文档处理死信队列
   */
  @Bean
  public Queue docDlqQueue() {
    return QueueBuilder.durable(DOC_DLQ_QUEUE).build();
  }

  /**
   * 绑定：文档延迟交换机 → 文档重试队列
   */
  @Bean
  public Binding docRetryBinding(Queue docRetryQueue, CustomExchange docDelayedExchange) {
    return new Binding(
            DOC_RETRY_QUEUE,
            Binding.DestinationType.QUEUE,
            DOC_DELAYED_EXCHANGE,
            DOC_RETRY_ROUTING_KEY,
            null
    );
  }

  /**
   * 绑定：文档死信交换机 → 文档死信队列
   */
  @Bean
  public Binding docDlqBinding() {
    return BindingBuilder.bind(docDlqQueue())
            .to(docDlqExchange())
            .with(DOC_DLQ_ROUTING_KEY);
  }
}
