package com.agenthub.api.mq.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文档处理重试消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocProcessRetryMessage implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * 知识库ID
   */
  private Long knowledgeId;

  /**
   * 用户ID
   */
  private Long userId;

  /**
   * 文件名
   */
  private String fileName;

  /**
   * 重试次数
   */
  @Builder.Default
  private int retryCount = 0;

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
   * 错误类型（用于判断是否可重试）
   * 如: RATE_LIMIT(限流), TIMEOUT(超时), NETWORK(网络错误), OTHER(其他)
   */
  private String errorType;

  /**
   * 时间戳
   */
  private Long timestamp;

  /**
   * 是否需要重试
   */
  public boolean needRetry() {
    return retryCount < maxRetryCount && isRetryableError();
  }

  /**
   * 是否为可重试的错误
   */
  public boolean isRetryableError() {
    if (errorType == null) {
      return true; // 默认可重试
    }
    return switch (errorType) {
      case "RATE_LIMIT", "TIMEOUT", "NETWORK", "DATA_BASE" -> true;
      default -> false;
    };
  }

  /**
   * 创建下一次重试消息
   */
  public DocProcessRetryMessage nextRetry(String errorMessage, String errorType) {
    return DocProcessRetryMessage.builder()
            .knowledgeId(this.knowledgeId)
            .userId(this.userId)
            .fileName(this.fileName)
            .retryCount(this.retryCount + 1)
            .maxRetryCount(this.maxRetryCount)
            .errorMessage(errorMessage)
            .errorType(errorType)
            .timestamp(System.currentTimeMillis())
            .build();
  }

  /**
   * 错误类型枚举
   */
  public enum ErrorType {
    RATE_LIMIT("限流"),
    TIMEOUT("超时"),
    NETWORK("网络错误"),
    DATA_BASE("数据库错误"),
    PARSE_ERROR("解析错误"),
    OTHER("其他");

    private final String desc;

    ErrorType(String desc) {
      this.desc = desc;
    }

    public String getDesc() {
      return desc;
    }

    public String getCode() {
      return this.name();
    }
  }
}
