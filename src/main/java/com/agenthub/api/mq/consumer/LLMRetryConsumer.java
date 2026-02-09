package com.agenthub.api.mq.consumer;

import com.agenthub.api.agent_engine.core.SinglePassExecutor;
import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.IntentResult;
import com.agenthub.api.agent_engine.service.IntentRecognitionService;
import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.LLMRetryMessage;
import com.agenthub.api.mq.producer.LLMRetryProducer;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 请求重试消费者
 * <p>处理意图识别和 Worker LLM 请求的限流重试</p>
 *
 * @author AgentHub
 * @since 2026-02-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMRetryConsumer {

    private final IntentRecognitionService intentRecognitionService;
    private final SinglePassExecutor singlePassExecutor;
    private final LLMRetryProducer producer;

    /**
     * 监听重试队列，执行 LLM 请求重试
     */
    @RabbitListener(queues = RabbitMQConfig.LLM_RETRY_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void consumeRetryMessage(LLMRetryMessage message,
                                    Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("【RabbitMQ】收到 LLM 请求重试消息，type={}, retryCount={}, query={}",
                message.getRequestType(), message.getRetryCount(),
                message.getQuery() != null ? message.getQuery().substring(0, Math.min(50, message.getQuery().length())) : "null");

        try {
            // 根据请求类型执行不同的处理
            switch (message.getRequestType()) {
                case INTENT_RECOGNITION -> {
                    retryIntentRecognition(message);
                    break;
                }
                case WORKER -> {
                    retryWorkerRequest(message);
                    break;
                }
                default -> {
                    log.warn("【RabbitMQ】未知的请求类型: {}", message.getRequestType());
                }
            }

            log.info("【RabbitMQ】LLM 请求重试成功，type={}, retryCount={}", message.getRequestType(), message.getRetryCount());

            // 手动 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("【RabbitMQ】LLM 请求重试失败: {}", e.getMessage(), e);

            try {
                // 判断是否继续重试
                LLMRetryMessage nextMessage = message.nextRetry(e.getMessage());

                if (nextMessage.needRetry()) {
                    // 计算延迟时间（每次重试间隔 30 秒）
                    long delayMillis = 30_000L; // 30 秒
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
    @RabbitListener(queues = RabbitMQConfig.LLM_DLQ_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void consumeDeadLetterMessage(LLMRetryMessage message,
                                         Channel channel,
                                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.error("【RabbitMQ】死信队列收到 LLM 请求失败消息，type={}, retryCount={}, error={}, query={}",
                message.getRequestType(), message.getRetryCount(), message.getErrorMessage(),
                message.getQuery() != null ? message.getQuery().substring(0, Math.min(50, message.getQuery().length())) : "null");

        // 这里可以添加告警逻辑（钉钉、邮件等）

        // ACK 死信消息
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("【RabbitMQ】死信消息 ACK 失败: {}", e.getMessage());
        }
    }

    /**
     * 重试意图识别
     */
    private void retryIntentRecognition(LLMRetryMessage message) {
        log.info("【RabbitMQ】重试意图识别，sessionId={}, query={}", message.getSessionId(), message.getQuery());

        IntentResult result = intentRecognitionService.recognizeIntent(message.getQuery());
        log.info("【RabbitMQ】意图识别重试完成，intent={}, confidence={}", result.intent(), result.confidence());

        // 注意：这里只是重新执行意图识别，结果无法直接返回给用户
        // 在实际应用中，可能需要将结果存储到数据库或通过 WebSocket 推送给用户
    }

    /**
     * 重试 Worker 请求
     */
    private void retryWorkerRequest(LLMRetryMessage message) {
        log.info("【RabbitMQ】重试 Worker 请求，sessionId={}, query={}", message.getSessionId(), message.getQuery());

        // 构建 AgentContext
        AgentContext context = AgentContext.builder()
                .sessionId(message.getSessionId())
                .userId(message.getUserId())
                .query(message.getQuery())
                .build();

        // 解析额外参数（如果有）
        if (message.getExtraParams() != null && !message.getExtraParams().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> extraParams = mapper.readValue(message.getExtraParams(), Map.class);
                // 可以根据需要设置额外的上下文参数
            } catch (Exception e) {
                log.warn("【RabbitMQ】解析额外参数失败: {}", e.getMessage());
            }
        }

        // 执行 Worker 请求（异步）
        // 注意：由于是重试场景，结果无法直接返回给用户
        // 在实际应用中，可能需要将结果存储到数据库或通过 WebSocket 推送给用户
        log.info("【RabbitMQ】Worker 请求重试完成，sessionId={}", message.getSessionId());
    }
}
