package com.agenthub.api.ai.core;


  public record AIRequest(
          String query,       // 用户问题
          String docContent,  // 文档内容
          String userId,      // 用户ID
          String sessionId    // 会话ID (用于记忆隔离)
  ) {
}
