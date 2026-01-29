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
   *
   * @param userId 用户ID
   * @return SseEmitter
   */
  public SseEmitter createEmitter(Long userId) {
    SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30分钟超时

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

    // 发送连接成功消息
    sendMessage(userId, Map.of(
            "type", "connected",
            "message", "实时推送已连接"
    ));

    log.info("【SSE】用户 {} 创建连接，当前连接数: {}", userId, userEmitters.getOrDefault(userId, new CopyOnWriteArrayList<>()).size());
    return emitter;
  }

  /**
   * 向指定用户推送消息
   *
   * @param userId 用户ID
   * @param data   消息数据
   */
  public void sendMessage(Long userId, Object data) {
    CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }

    // 使用迭代器安全地遍历，移除已失效的 emitter
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

    // 如果该用户没有有效连接了，清理
    if (emitters.isEmpty()) {
      userEmitters.remove(userId);
    }
  }

  /**
   * 广播消息（推送给所有在线用户）
   *
   * @param data 消息数据
   */
  public void broadcast(Object data) {
    userEmitters.keySet().forEach(userId -> sendMessage(userId, data));
  }

  /**
   * 推送知识库状态变化
   * <p>
   * 根据 status 映射为前端期望的消息类型：
   * - "1" (处理中) -> "processing"
   * - "2" (已完成) -> "completed" (附带 vectorCount)
   * - "3" (失败) -> "failed"
   * </p>
   *
   * @param userId      用户ID
   * @param knowledgeId 知识库ID
   * @param status      状态（0未处理 1处理中 2已完成 3失败）
   * @param vectorCount 向量数量
   */
  public void pushKnowledgeStatus(Long userId, Long knowledgeId, String status, Integer vectorCount) {
    String messageType = switch (status) {
      case "1" -> "processing";
      case "2" -> "completed";
      case "3" -> "failed";
      default -> null; // 状态 "0" 不发送通知
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

  /**
   * 移除失效的 emitter
   */
  private void removeEmitter(Long userId, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) {
        userEmitters.remove(userId);
      }
    }
  }

  /**
   * 获取当前在线用户数
   */
  public int getOnlineUserCount() {
    return userEmitters.size();
  }
}
