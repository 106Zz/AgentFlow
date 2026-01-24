package com.agenthub.api.search.domain;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * BM25词频实体
 *
 * 存储每个词在每个文档中的出现次数
 */
@Data
@TableName("bm25_term_freq")
public class Bm25TermFreq {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 词项
     */
    private String term;

    /**
     * 文档ID（对应 vector_store.id）
     */
    private String docId;

    /**
     * 词频
     */
    private Integer frequency;
}
