package com.agenthub.api.framework.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 实时推送服务
 * <p>
 * 用于向前端推送知识库处理状态变化
 * v5.0 - 新增 "4" -> "partial" 状态映射
 * </p>
 */
@Slf4j
@Service
public class SseEmitterService {

  /**
   * 存储每个用户的 SSE 连接
   * key: userId, value: SseEmitter 列表（支持多标签页）
   */
  private final Map<Long, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

  /**
   * 创建 SSE 连接
   */
  public SseEmitter createEmitter(Long userId) {
    SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

    emitter.onCompletion(() -> {
      log.info("【SSE】用户 {} 连接关闭", userId);
      removeEmitter(userId, emitter);
    });

    emitter.onTimeout(() -> {
      log.info("【SSE】用户 {} 连接超时", userId);
      removeEmitter(userId, emitter);
    });

    emitter.onError((e) -> {
      log.error("【SSE】用户 {} 连接异常: {}", userId, e.getMessage());
      removeEmitter(userId, emitter);
    });

    userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

    sendMessage(userId, Map.of(
            "type", "connected",
            "message", "实时推送已连接"
    ));

    log.info("【SSE】用户 {} 创建连接，当前连接数: {}", userId, userEmitters.getOrDefault(userId, new CopyOnWriteArrayList<>()).size());
    return emitter;
  }

  /**
   * 向指定用户推送消息
   */
  public void sendMessage(Long userId, Object data) {
    CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    emitters.removeIf(emitter -> {
      try {
        emitter.send(SseEmitter.event()
                .data(data)
                .id(String.valueOf(System.currentTimeMillis()))
                .name("message"));
        return false;
      } catch (IOException e) {
        log.warn("【SSE】发送消息失败，移除连接: {}", e.getMessage());
        return true;
      }
    });

    if (emitters.isEmpty()) {
      userEmitters.remove(userId);
    }
  }

  /**
   * 广播消息（推送给所有在线用户）
   */
  public void broadcast(Object data) {
    userEmitters.keySet().forEach(userId -> sendMessage(userId, data));
  }

  /**
   * 推送知识库状态变化
   * <p>
   * 状态映射：
   * - "1" -> "processing"（处理中）
   * - "2" -> "completed"（已完成）
   * - "3" -> "failed"（失败）
   * - "4" -> "partial"（部分成功）
   * </p>
   */
  public void pushKnowledgeStatus(Long userId, Long knowledgeId, String status, Integer vectorCount) {
    String messageType = switch (status) {
      case "1" -> "processing";
      case "2" -> "completed";
      case "3" -> "failed";
      case "4" -> "partial";
      default -> null;
    };

    if (messageType == null) {
      return;
    }

    sendMessage(userId, Map.of(
            "type", messageType,
            "knowledgeId", knowledgeId,
            "vectorCount", vectorCount != null ? vectorCount : 0,
            "timestamp", System.currentTimeMillis()
    ));
  }

  private void removeEmitter(Long userId, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) {
        userEmitters.remove(userId);
      }
    }
  }

  public int getOnlineUserCount() {
    return userEmitters.size();
  }
}
