package com.agenthub.api.mq.producer;

import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.LLMRetryMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * LLM 请求重试消息生产者
 * <p>用于处理意图识别和 Worker LLM 请求的限流重试</p>
 *
 * @author AgentHub
 * @since 2026-02-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMRetryProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送延迟重试消息
     *
     * @param message     重试消息
     * @param delayMillis 延迟毫秒数
     */
    public void sendRetryMessage(LLMRetryMessage message, long delayMillis) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LLM_DELAYED_EXCHANGE,
                    RabbitMQConfig.LLM_RETRY_ROUTING_KEY,
                    message,
                    msg -> {
                        // 延迟插件使用 x-delay header 设置延迟时间
                        msg.getMessageProperties().setHeader("x-delay", (int) delayMillis);
                        return msg;
                    }
            );
            log.info("【RabbitMQ】LLM 请求重试消息已发送，type={}, retryCount={}, delay={}ms, query={}",
                    message.getRequestType(), message.getRetryCount(), delayMillis,
                    message.getQuery() != null ? message.getQuery().substring(0, Math.min(50, message.getQuery().length())) : "null");
        } catch (Exception e) {
            log.error("【RabbitMQ】发送 LLM 重试消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送消息到死信队列
     *
     * @param message 失败消息
     */
    public void sendToDeadLetterQueue(LLMRetryMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.LLM_DLQ_EXCHANGE,
                    RabbitMQConfig.LLM_DLQ_ROUTING_KEY,
                    message
            );
            log.error("【RabbitMQ】LLM 请求已达到最大重试次数，发送到死信队列，type={}, retryCount={}, query={}",
                    message.getRequestType(), message.getRetryCount(),
                    message.getQuery() != null ? message.getQuery().substring(0, Math.min(50, message.getQuery().length())) : "null");
        } catch (Exception e) {
            log.error("【RabbitMQ】发送死信队列失败: {}", e.getMessage(), e);
        }
    }
}
