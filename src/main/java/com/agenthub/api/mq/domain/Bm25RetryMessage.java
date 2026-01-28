package com.agenthub.api.mq.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * BM25 重建重试消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Bm25RetryMessage implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * 重试次数
   */
  private int retryCount;

  /**
   * 最大重试次数
   */
  @Builder.Default
  private int maxRetryCount = 3;

  /**
   * 错误信息（上一次失败原因）
   */
  private String errorMessage;

  /**
   * 时间戳
   */
  private Long timestamp;

  /**
   * 是否需要重试
   */
  public boolean needRetry() {
    return retryCount < maxRetryCount;
  }

  /**
   * 创建下一次重试消息
   */
  public Bm25RetryMessage nextRetry(String errorMessage) {
    return Bm25RetryMessage.builder()
            .retryCount(this.retryCount + 1)
            .maxRetryCount(this.maxRetryCount)
            .errorMessage(errorMessage)
            .timestamp(System.currentTimeMillis())
            .build();
  }
}
