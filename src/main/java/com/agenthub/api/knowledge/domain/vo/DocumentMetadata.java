package com.agenthub.api.knowledge.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档元数据（用于向量存储）
 * 这些数据会被存储到 vector_store 表的 metadata 字段中
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata {

    /**
     * 知识库ID（关联 knowledge_base 表）
     */
    private Long knowledgeId;

    /**
     * 用户ID（用于数据隔离）
     * 0 表示全局知识库，其他值表示用户私有
     */
    private Long userId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件类型
     */
    private String fileType;

    /**
     * 分类
     */
    private String category;

    /**
     * 标签
     */
    private String tags;

    /**
     * 是否公开（0私有 1公开）
     */
    private String isPublic;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 页码或章节（用于定位）
     */
    private Integer pageNumber;

    /**
     * 文档块索引
     */
    private Integer chunkIndex;
}
