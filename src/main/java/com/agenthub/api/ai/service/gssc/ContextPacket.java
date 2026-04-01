package com.agenthub.api.ai.service.gssc;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * GSSC 上下文数据包
 * <p>
 * 用于封装待评分和选择的内容单元，可以是：
 * - RAG 检索结果
 * - 历史对话消息
 * - 系统指令
 * </p>
 */
@Data
@Builder
public class ContextPacket {

    /**
     * 内容类型
     */
    private ContextType type;

    /**
     * 内容文本
     */
    private String content;

    /**
     * 创建/发生时间
     */
    private Instant timestamp;

    /**
     * 预估 Token 数量
     */
    private int tokenCount;

    /**
     * 相关性分数（向量检索分数）
     */
    private double relevanceScore;

    /**
     * 额外元数据
     */
    private java.util.Map<String, Object> metadata;

    /**
     * 综合评分（GSSC Select 阶段计算）
     */
    @Builder.Default
    private double score = 0.0;

    /**
     * 内容类型枚举
     */
    public enum ContextType {
        /** RAG 检索结果 */
        EVIDENCE,
        /** 历史对话消息 */
        HISTORY,
        /** 系统指令 */
        SYSTEM,
        /** 用户当前问题 */
        QUERY,
        /** 工具执行结果 */
        TOOL_RESULT
    }

    /**
     * 快速构建方法
     */
    public static ContextPacket evidence(String content, Instant timestamp, int tokenCount, double relevanceScore) {
        return ContextPacket.builder()
                .type(ContextType.EVIDENCE)
                .content(content)
                .timestamp(timestamp)
                .tokenCount(tokenCount)
                .relevanceScore(relevanceScore)
                .build();
    }

    public static ContextPacket history(String content, Instant timestamp, int tokenCount) {
        return ContextPacket.builder()
                .type(ContextType.HISTORY)
                .content(content)
                .timestamp(timestamp)
                .tokenCount(tokenCount)
                .build();
    }

    public static ContextPacket toolResult(String content, String toolName, int tokenCount) {
        return ContextPacket.builder()
                .type(ContextType.TOOL_RESULT)
                .content(content)
                .timestamp(Instant.now())
                .tokenCount(tokenCount)
                .relevanceScore(0.9)
                .metadata(Map.of("toolName", toolName))
                .build();
    }
}
