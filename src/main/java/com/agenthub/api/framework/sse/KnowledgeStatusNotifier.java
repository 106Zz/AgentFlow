package com.agenthub.api.framework.sse;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库状态推送助手
 * <p>
 * 提供统一的状态推送接口，内部使用 SSE 实时推送
 * v5.0 - 新增 notifyPartial 方法，支持 PARTIAL 状态推送
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeStatusNotifier {

  private final SseEmitterService sseEmitterService;

  /**
   * 推送知识库状态变化
   *
   * @param userId      用户ID
   * @param knowledgeId 知识库ID
   * @param status      状态（0未处理 1处理中 2已完成 3失败 4部分成功）
   * @param vectorCount 向量数量
   */
  public void notifyStatusChange(Long userId, Long knowledgeId, String status, Integer vectorCount) {
    if (userId == null) {
      return;
    }
    sseEmitterService.pushKnowledgeStatus(userId, knowledgeId, status, vectorCount);
  }

  /**
   * 推送处理中状态
   */
  public void notifyProcessing(Long userId, Long knowledgeId) {
    notifyStatusChange(userId, knowledgeId, "1", null);
  }

  /**
   * 推送完成状态
   */
  public void notifyCompleted(Long userId, Long knowledgeId, int vectorCount) {
    notifyStatusChange(userId, knowledgeId, "2", vectorCount);
  }

  /**
   * 推送失败状态
   */
  public void notifyFailed(Long userId, Long knowledgeId, String errorMsg) {
    if (userId == null) {
      return;
    }
    sseEmitterService.sendMessage(userId, Map.of(
        "type", "failed",
        "knowledgeId", knowledgeId,
        "error", errorMsg != null ? errorMsg : "未知错误",
        "timestamp", System.currentTimeMillis()
    ));
    log.warn("【状态推送】知识库 {} 处理失败: {}", knowledgeId, errorMsg);
  }

  /**
   * 推送部分成功状态
   *
   * @param userId         用户ID
   * @param knowledgeId    知识库ID
   * @param vectorCount    入库块数
   * @param failedPages    失败页数
   * @param failedPageNums 失败页码列表
   */
  public void notifyPartial(Long userId, Long knowledgeId, int vectorCount,
                            int failedPages, List<Integer> failedPageNums) {
    if (userId == null) {
      return;
    }
    sseEmitterService.sendMessage(userId, Map.of(
        "type", "partial",
        "knowledgeId", knowledgeId,
        "vectorCount", vectorCount,
        "failedPages", failedPages,
        "failedPageNums", failedPageNums != null ? failedPageNums : List.of(),
        "timestamp", System.currentTimeMillis()
    ));
    log.warn("【状态推送】知识库 {} 部分成功: 入库 {} 块, 失败 {} 页, 失败页码: {}",
            knowledgeId, vectorCount, failedPages, failedPageNums);
  }
}
