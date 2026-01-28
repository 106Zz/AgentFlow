package com.agenthub.api.mq.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 监听器配置
 */
@Configuration
public class RabbitMqListenerConfig {

  /**
   * JSON 消息转换器
   */
  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  /**
   * 监听器容器工厂
   */
  @Bean
  public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
          ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter());
    factory.setConcurrentConsumers(1);  // 并发消费者数量
    factory.setMaxConcurrentConsumers(3);  // 最大并发消费者数量
    factory.setPrefetchCount(1);  // 预取数量
    factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);  // 手动 ACK
    return factory;
  }

  /**
   * 配置 RabbitTemplate 使用 JSON 转换器
   */
  @Bean
  public RabbitTemplate rabbitTemplate(org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter());
    return template;
  }
}
