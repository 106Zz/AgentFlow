package com.agenthub.api.knowledge.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量确认上传响应
 */
@Data
public class BatchConfirmResponse {

    /**
     * 成功触发处理的文件数
     */
    private Integer successCount;

    /**
     * 已处理无需重复触发的文件数
     */
    private Integer skippedCount;

    /**
     * 失败的文件数
     */
    private Integer failedCount;

    /**
     * 详细结果列表
     */
    private List<ConfirmResult> results;

    /**
     * 单个确认结果
     */
    @Data
    public static class ConfirmResult {

        /**
         * 知识库记录 ID
         */
        private Long knowledgeId;

        /**
         * 文件名
         */
        private String fileName;

        /**
         * 状态：success-成功触发, skipped-已处理跳过, error-错误
         */
        private String status;

        /**
         * 错误信息（status=error 时有值）
         */
        private String errorMsg;
    }
}
