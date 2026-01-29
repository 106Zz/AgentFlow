package com.agenthub.api.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量确认上传请求
 */
@Data
public class BatchConfirmRequest {

    /**
     * 确认信息列表
     */
    private List<ConfirmInfo> confirms;

    /**
     * 确认信息
     */
    @Data
    public static class ConfirmInfo {

        /**
         * 知识库记录 ID（必填）
         */
        @NotNull(message = "知识库ID不能为空")
        private Long knowledgeId;

        /**
         * OSS 文件路径（必填）
         */
        private String filePath;

        /**
         * 文件大小（字节）
         */
        private Long fileSize;
    }
}
