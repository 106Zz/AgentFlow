package com.agenthub.api.framework.sse;

import com.agenthub.api.framework.security.service.TokenService;
import com.agenthub.api.system.domain.model.LoginUser;
import io.jsonwebtoken.Claims;
import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 实时推送 Controller
 */
@Slf4j
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class SseController {

  private final SseEmitterService sseEmitterService;
  private final TokenService tokenService;

  /**
   * 建立 SSE 连接
   * 前端示例:
   * const eventSource = new EventSource('/sse/connect?token=xxx');
   * eventSource.onmessage = (e) => { console.log(e.data); };
   */
  @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter connect(@RequestParam(required = false) String token) {
    Long userId = parseUserId(token);
    if (userId == null) {
      log.warn("【SSE】无法解析用户 ID，连接请求被拒绝");
      // 返回一个立即关闭的 emitter
      SseEmitter errorEmitter = new SseEmitter(1000L);
      try {
        errorEmitter.send(SseEmitter.event()
                .data(Map.of("error", "Unauthorized", "message", "无效的 token"))
                .name("error"));
      } catch (IOException e) {
        // ignore
      }
      errorEmitter.complete();
      return errorEmitter;
    }

    log.info("【SSE】用户 {} 建立连接", userId);
    return sseEmitterService.createEmitter(userId);
  }

  /**
   * 从 token 解析用户 ID
   */
  private Long parseUserId(String token) {
    if (token == null || token.isEmpty()) {
      return null;
    }

    try {
      // 使用 TokenService 的方法解析 token
      Claims claims = tokenService.parseTokenDirect(token);
      if (claims != null) {
        Object userIdObj = claims.get("userid"); // 注意：JWT 中是 "userid" 全小写
        if (userIdObj instanceof Number) {
          return ((Number) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
          return Long.parseLong((String) userIdObj);
        }
      }
    } catch (Exception e) {
      log.error("【SSE】解析 token 失败: {}", e.getMessage());
    }

    return null;
  }

  /**
   * 获取在线用户数（调试用）
   */
  @GetMapping("/online-count")
  public int getOnlineCount() {
    return sseEmitterService.getOnlineUserCount();
  }
}
