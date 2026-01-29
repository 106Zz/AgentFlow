package com.agenthub.api.framework.sse;

import com.agenthub.api.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 实时推送 Controller
 */
@RestController
@RequestMapping("/api/sse")
@RequiredArgsConstructor
public class SseController {

  private final SseEmitterService sseEmitterService;

  /**
   * 建立 SSE 连接
   * 前端示例:
   * const eventSource = new EventSource('/api/sse/connect');
   * eventSource.onmessage = (e) => { console.log(e.data); };
   */
  @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter connect() {
    // 从 SecurityContext 获取当前用户 ID
    // 这里简化处理，实际应该从 JWT 中获取
    Long userId = getCurrentUserId();
    return sseEmitterService.createEmitter(userId);
  }

  /**
   * 获取在线用户数（调试用）
   */
  @GetMapping("/online-count")
  public int getOnlineCount() {
    return sseEmitterService.getOnlineUserCount();
  }

  private Long getCurrentUserId() {
    return SecurityUtils.getUserId();
  }
}
