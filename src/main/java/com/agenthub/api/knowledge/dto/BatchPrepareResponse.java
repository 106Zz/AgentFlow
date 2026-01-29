package com.agenthub.api.knowledge.dto;

import lombok.Data;

import java.util.Map;

/**
 * 批量准备上传响应
 */
@Data
public class BatchPrepareResponse {

    /**
     * 准备成功的文件数
     */
    private Integer successCount;

    /**
     * 复用已有记录的文件数
     */
    private Integer skippedCount;

    /**
     * 文件上传信息列表
     */
    private java.util.List<FileUploadInfo> files;

    /**
     * 文件上传信息
     */
    @Data
    public static class FileUploadInfo {

        /**
         * 知识库记录 ID
         */
        private Long knowledgeId;

        /**
         * 文件名
         */
        private String fileName;

        /**
         * OSS 上传策略
         */
        private Map<String, String> uploadPolicy;

        /**
         * 状态：new-新建, skipped-跳过（已存在）, error-错误
         */
        private String status;

        /**
         * 错误信息（status=error 时有值）
         */
        private String errorMsg;
    }
}
