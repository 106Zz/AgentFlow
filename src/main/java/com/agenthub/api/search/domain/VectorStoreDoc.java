package com.agenthub.api.search.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Vector Store 文档实体
 * <p>
 * 对应 Spring AI PGVector 自动创建的 vector_store 表
 * </p>
 * <p>
 * 表结构：
 * - id: UUID (主键)
 * - content: TEXT (文档内容)
 * - metadata: JSONB (元数据)
 * - embedding: VECTOR (向量字段，BM25 检索不需要)
 * </p>
 */
@Data
@TableName("vector_store")
public class VectorStoreDoc {

    /**
     * 主键 UUID（vector_store.id）
     * 注意：这不是 internal_id，internal_id 存储在 metadata->>'internal_id' 中
     */
    @TableField("id")
    private String id;

    /**
     * 文档内容
     */
    @TableField("content")
    private String content;

    /**
     * 元数据（JSONB 格式）
     */
    @TableField("metadata")
    private String metadata;

    /**
     * 向量字段（BM25 检索不需要使用，标记为不存在）
     */
    @TableField(exist = false)
    private Object embedding;
}
