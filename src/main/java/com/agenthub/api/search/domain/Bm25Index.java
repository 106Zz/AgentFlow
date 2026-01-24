package com.agenthub.api.search.domain;

import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * BM25索引实体
 *
 * 存储每个 chunk（vector_store记录）的分词信息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bm25_index")
public class Bm25Index extends BaseEntity {


    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 内部ID（对应 vector_store.metadata.internal_id）
     * 注意：数据库列名仍是 vector_id，待数据库迁移后再改
     */
    @TableField("vector_id")  // 数据库列名暂时保持 vector_id
    private String internalId;

    /**
     * 知识库ID
     */
    private Long knowledgeId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 分词结果（JSON数组字符串，如 ["功率因数", "调整", "电费"]）
     */
    private String tokens;

    /**
     * 词数（chunk长度）
     */
    private Integer tokenCount;
}
