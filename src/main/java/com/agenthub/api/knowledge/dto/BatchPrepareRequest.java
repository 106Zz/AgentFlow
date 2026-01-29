package com.agenthub.api.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 批量准备上传请求
 */
@Data
public class BatchPrepareRequest {

    /**
     * 文件信息列表
     */
    private List<FileMeta> files;

    /**
     * 文件元数据
     */
    @Data
    public static class FileMeta {

        /**
         * 文件名（必填）
         */
        @NotBlank(message = "文件名不能为空")
        private String fileName;

        /**
         * 知识标题（可选，默认使用文件名）
         */
        private String title;

        /**
         * 文件类型（可选，自动从文件名提取）
         */
        private String fileType;

        /**
         * 知识分类
         */
        private String category;

        /**
         * 标签
         */
        private String tags;
    }
}
