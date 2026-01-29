package com.agenthub.api.mq.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文件上传消息
 * 用于 RabbitMQ 异步上传处理
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 知识库ID（数据库记录ID）
     */
    private Long knowledgeId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件标题
     */
    private String title;

    /**
     * 文件类型（扩展名）
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 临时文件路径（如果文件先上传到服务器临时目录）
     * 可选：如果使用 OSS 直传凭证方式，此字段为空
     */
    private String tempFilePath;

    /**
     * OSS 上传策略（JSON 格式）
     * 如果使用前端直传 OSS 方式
     */
    private String uploadPolicy;

    /**
     * 是否管理员
     */
    private Boolean isAdmin;

    /**
     * 创建时间
     */
    private Long createTime;

    /**
     * 重试次数
     */
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private Integer maxRetryCount = 3;

    /**
     * 是否需要重试
     */
    public boolean needRetry() {
        return retryCount < maxRetryCount;
    }

    /**
     * 创建下一次重试消息
     */
    public FileUploadMessage nextRetry(String errorMessage) {
        return FileUploadMessage.builder()
                .knowledgeId(this.knowledgeId)
                .userId(this.userId)
                .fileName(this.fileName)
                .title(this.title)
                .fileType(this.fileType)
                .fileSize(this.fileSize)
                .tempFilePath(this.tempFilePath)
                .uploadPolicy(this.uploadPolicy)
                .isAdmin(this.isAdmin)
                .createTime(System.currentTimeMillis())
                .retryCount(this.retryCount + 1)
                .maxRetryCount(this.maxRetryCount)
                .build();
    }
}
