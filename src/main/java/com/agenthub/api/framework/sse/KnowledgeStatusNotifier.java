package com.agenthub.api.framework.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 知识库状态推送助手
 * <p>
 * 提供统一的状态推送接口，内部使用 SSE 实时推送
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
   * @param status      状态（0未处理 1处理中 2已完成 3失败）
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
    notifyStatusChange(userId, knowledgeId, "3", null);
    log.warn("【状态推送】知识库 {} 处理失败: {}", knowledgeId, errorMsg);
  }
}
