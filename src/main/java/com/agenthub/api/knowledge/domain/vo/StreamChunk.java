package com.agenthub.api.knowledge.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式响应分块（包含思考过程与回答内容）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreamChunk {
    /**
     * 模型的思考过程（DeepSeek R1 等推理模型特有）
     * 可能为 null
     */
    private String reasoning;

    /**
     * 模型的最终回答内容
     */
    private String content;

    /**
     * 会话ID (用于前端关联上下文)
     */
    private String sessionId;
}
