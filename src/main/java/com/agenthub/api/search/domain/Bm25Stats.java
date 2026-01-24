package com.agenthub.api.search.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * BM25全局统计实体
 *
 * 存储全局统计信息：总文档数、平均文档长度等
 */
@Data
@TableName("bm25_stats")
public class Bm25Stats implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 统计项（如 'total_docs', 'avg_doc_length'）
     */
    @TableId
    private String key;

    /**
     * 值
     */
    private Double value;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
