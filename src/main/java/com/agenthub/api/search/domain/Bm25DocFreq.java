package com.agenthub.api.search.domain;


import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * BM25文档频率实体
 *
 * 存储每个词出现在多少个文档中（用于计算IDF）
 */
@Data
@TableName("bm25_doc_freq")
public class Bm25DocFreq implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * 词项（主键）
   */
  @TableId
  private String term;

  /**
   * 包含该词的文档数量
   */
  private Integer docCount;

  /**
   * IDF值（可选预计算）
   */
  private Double idf;

  /**
   * 更新时间
   */
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updateTime;
}
