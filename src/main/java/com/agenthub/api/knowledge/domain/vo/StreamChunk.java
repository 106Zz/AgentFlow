package com.agenthub.api.knowledge.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式响应块
 * 用于区分思考内容和回答内容
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreamChunk {
    
    /**
     * 内容类型
     * - reasoning: 思考过程
     * - answer: 最终回答
     * - done: 完成标记
     */
    private String type;
    
    /**
     * 内容
     */
    private String content;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    public static StreamChunk reasoning(String content) {
        return new StreamChunk("reasoning", content, null);
    }
    
    public static StreamChunk answer(String content) {
        return new StreamChunk("answer", content, null);
    }
    
    public static StreamChunk done(String sessionId) {
        return new StreamChunk("done", "", sessionId);
    }
}
