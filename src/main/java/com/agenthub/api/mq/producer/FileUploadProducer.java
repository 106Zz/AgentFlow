package com.agenthub.api.mq.producer;

import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.FileUploadMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 文件上传消息生产者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileUploadProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送文件上传消息到队列
     *
     * @param message 上传消息
     */
    public void sendUploadMessage(FileUploadMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.FILE_UPLOAD_EXCHANGE,
                    RabbitMQConfig.FILE_UPLOAD_ROUTING_KEY,
                    message
            );
            log.info("【RabbitMQ】文件上传消息已发送，知识库ID: {}, 文件名: {}",
                    message.getKnowledgeId(), message.getFileName());
        } catch (Exception e) {
            log.error("【RabbitMQ】发送文件上传消息失败: {}", e.getMessage(), e);
            throw new RuntimeException("发送上传消息失败", e);
        }
    }

    /**
     * 发送失败消息到死信队列
     *
     * @param message 失败消息
     * @param errorMessage 错误信息
     */
    public void sendToDeadLetterQueue(FileUploadMessage message, String errorMessage) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.FILE_UPLOAD_DLQ_EXCHANGE,
                    RabbitMQConfig.FILE_UPLOAD_DLQ_ROUTING_KEY,
                    message
            );
            log.error("【RabbitMQ】文件上传已失败，发送到死信队列，知识库ID: {}, 文件名: {}, 错误: {}",
                    message.getKnowledgeId(), message.getFileName(), errorMessage);
        } catch (Exception e) {
            log.error("【RabbitMQ】发送文件上传死信队列失败: {}", e.getMessage(), e);
        }
    }
}
