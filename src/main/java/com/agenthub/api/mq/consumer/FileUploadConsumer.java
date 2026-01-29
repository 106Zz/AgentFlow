package com.agenthub.api.mq.consumer;

import com.agenthub.api.ai.service.impl.DocumentProcessServiceImpl;
import com.agenthub.api.common.utils.OssUtils;
import com.agenthub.api.framework.sse.KnowledgeStatusNotifier;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import com.agenthub.api.mq.config.RabbitMQConfig;
import com.agenthub.api.mq.domain.FileUploadMessage;
import com.agenthub.api.mq.producer.FileUploadProducer;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 文件上传消费者
 * 处理异步文件上传和文档处理流程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileUploadConsumer {

    private final FileUploadProducer producer;
    private final IKnowledgeBaseService knowledgeBaseService;
    private final KnowledgeStatusNotifier statusNotifier;
    private final DocumentProcessServiceImpl documentProcessService;
    private final OssUtils ossUtils;

    /**
     * 监听文件上传队列
     *
     * 处理流程（v4.3 修复）：
     * 1. 从数据库获取知识库记录
     * 2. 读取本地临时文件
     * 3. 立即上传到 OSS（并行：前端已保存到临时文件）
     * 4. 更新数据库 filePath 为 OSS 路径
     * 5. 执行文档处理（直接从 OSS InputStream 读取，不下载！）
     * 6. 处理成功后删除临时文件
     * 7. 更新状态并通过 SSE 通知
     */
    @RabbitListener(queues = RabbitMQConfig.FILE_UPLOAD_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void consumeUploadMessage(FileUploadMessage message,
                                     Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("【RabbitMQ】收到文件上传消息，知识库ID: {}, 文件名: {}, 临时路径: {}",
                message.getKnowledgeId(), message.getFileName(), message.getTempFilePath());

        // 获取知识库记录
        KnowledgeBase knowledge = knowledgeBaseService.getById(message.getKnowledgeId());
        if (knowledge == null) {
            log.warn("【RabbitMQ】知识库不存在，跳过处理，ID: {}", message.getKnowledgeId());
            ackMessage(channel, deliveryTag);
            return;
        }

        File tempFile = null;
        boolean processingSuccess = false;
        try {
            // 推送处理中状态
            statusNotifier.notifyProcessing(message.getUserId(), message.getKnowledgeId());

            // 1. 读取本地临时文件
            tempFile = new File(message.getTempFilePath());
            if (!tempFile.exists()) {
                throw new IllegalStateException("临时文件不存在: " + message.getTempFilePath());
            }
            log.info("【后台上传】临时文件存在，大小: {} bytes", tempFile.length());

            // 2. 立即上传到 OSS（与前端临时文件保存并行）
            String ossPath = ossUtils.uploadFile(tempFile,
                    message.getIsAdmin() ? "knowledge/public/" : "knowledge/user/" + message.getUserId() + "/");
            log.info("【后台上传】文件已上传到 OSS: {}", ossPath);

            // 3. 更新数据库 filePath 为 OSS 路径
            knowledge.setFilePath(ossPath);
            knowledgeBaseService.updateById(knowledge);

            // 4. 执行文档处理（直接从 OSS InputStream 读取，不下载临时文件）
            int vectorCount = documentProcessService.processDocumentCore(knowledge);

            // 5. 更新状态为已完成
            updateStatusWithRetry(knowledge, "3", vectorCount);
            statusNotifier.notifyCompleted(message.getUserId(), knowledge.getId(), vectorCount);

            // 成功后 ACK
            channel.basicAck(deliveryTag, false);

            processingSuccess = true;

            log.info("【RabbitMQ】文件上传处理成功，知识库ID: {}, 向量数量: {}",
                    message.getKnowledgeId(), vectorCount);

        } catch (Exception e) {
            log.error("【RabbitMQ】文件上传处理失败: {}", e.getMessage(), e);

            try {
                // 判断是否需要重试
                if (message.needRetry() && isRetryableError(e)) {
                    // 重试
                    FileUploadMessage retryMessage = message.nextRetry(e.getMessage());
                    producer.sendUploadMessage(retryMessage);
                    log.info("【RabbitMQ】文件上传将重试，知识库ID: {}, 重试次数: {}",
                            message.getKnowledgeId(), retryMessage.getRetryCount());
                } else {
                    // 达到最大重试次数或不可重试，发送到死信队列
                    producer.sendToDeadLetterQueue(message, e.getMessage());

                    // 更新状态为失败
                    updateStatusWithRetry(knowledge, "4", null);
                    statusNotifier.notifyFailed(message.getUserId(), message.getKnowledgeId(), e.getMessage());

                    // 更新失败原因
                    try {
                        knowledge.setRemark("处理失败（已重试 " + message.getRetryCount() + " 次）: " + e.getMessage());
                        knowledgeBaseService.updateById(knowledge);
                    } catch (Exception remarkEx) {
                        log.warn("【remark更新失败】知识库 ID: {}", knowledge.getId(), remarkEx);
                    }
                }

                channel.basicAck(deliveryTag, false);

            } catch (Exception ex) {
                log.error("【RabbitMQ】处理失败逻辑异常: {}", ex.getMessage(), ex);
                try {
                    channel.basicNack(deliveryTag, false, true);
                } catch (Exception ignored) {
                }
            }
        } finally {
            // v4.3 - 只有处理成功才删除临时文件
            // 如果处理失败，保留临时文件以便手动重试时使用
            if (processingSuccess) {
                cleanupTempFile(tempFile);
            } else {
                log.warn("【文件保留】处理失败，保留临时文件以便重试: {}", tempFile != null ? tempFile.getPath() : "null");
            }
        }
    }

    /**
     * 监听文件上传死信队列
     */
    @RabbitListener(queues = RabbitMQConfig.FILE_UPLOAD_DLQ_QUEUE,
            containerFactory = "rabbitListenerContainerFactory")
    public void consumeDeadLetterMessage(FileUploadMessage message,
                                         Channel channel,
                                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.error("【RabbitMQ】死信队列收到文件上传失败消息，知识库ID: {}, 文件名: {}, 重试次数: {}",
                message.getKnowledgeId(), message.getFileName(), message.getRetryCount());

        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("【RabbitMQ】死信消息 ACK 失败: {}", e.getMessage());
        }
    }

    /**
     * 分类错误类型，判断是否可重试
     */
    private boolean isRetryableError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return true;
        }

        String lowerMsg = message.toLowerCase();
        // 以下错误类型可以重试
        if (lowerMsg.contains("rate limit") || lowerMsg.contains("429") ||
            lowerMsg.contains("timeout") || lowerMsg.contains("connection") ||
            lowerMsg.contains("network") || lowerMsg.contains("database") ||
            lowerMsg.contains("sql")) {
            return true;
        }

        // 解析错误通常不可重试
        if (lowerMsg.contains("parse") || lowerMsg.contains("corrupted") ||
            lowerMsg.contains("invalid format")) {
            return false;
        }

        return true; // 默认可重试
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFile(File tempFile) {
        if (tempFile != null && tempFile.exists()) {
            boolean deleted = tempFile.delete();
            log.info("【文件清理】临时文件删除: {}, 结果: {}", tempFile.getPath(), deleted ? "成功" : "失败");
        }
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
                    log.error("【状态更新最终失败】知识库 ID: {}, 状态: {}", knowledge.getId(), status);
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

    /**
     * 安全地 ACK 消息
     */
    private void ackMessage(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("【RabbitMQ】ACK 消息失败: {}", e.getMessage());
        }
    }
}
