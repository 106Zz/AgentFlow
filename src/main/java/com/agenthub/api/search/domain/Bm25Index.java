package com.agenthub.api.search.domain;

import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * BM25索引实体
 *
 * 存储每个文档（vector_store记录）的分词信息
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
     * 关联 vector_store.id
     */
    private String vectorId;

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
     * 词数（文档长度）
     */
    private Integer tokenCount;
}
