package com.agenthub.api.ai.domain;


public record ChatRequestDTO(
        String query,
        String docContent, // 可选：上传的文档内容
        String sessionId   // 可选：会话ID
) {
}
