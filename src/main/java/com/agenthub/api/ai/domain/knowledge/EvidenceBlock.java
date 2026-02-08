package com.agenthub.api.ai.domain.knowledge;

import lombok.Builder;

import java.util.List;

/**
 * 证据块（Evidence Block）
 * <p>多个语义相关的 chunk 组成的完整证据单元</p>
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>不摘要、不压缩、不改写原文</li>
 *   <li>保留完整的证据链（来源 + 页码 + chunk范围）</li>
 *   <li>同一来源（filename）且页码相邻（±1）的 chunk 可以合并</li>
 * </ul>
 *
 * <h3>与 Chunk 的区别：</h3>
 * <pre>
 * Chunk = 存储单位（固定大小切片）
 * EvidenceBlock = 引用单位（语义完整的事实陈述）
 * </pre>
 *
 * @author AgentHub
 * @since 2026-02-07
 */
@Builder
public record EvidenceBlock(
        /**
         * 证据内容（原文拼接，不改写）
         * <p>多个 chunk 的原始文本按顺序拼接</p>
         */
        String content,

        /**
         * 来源文件名
         */
        String filename,

        /**
         * 页码范围（起止页）
         * <p>如果只有一个 chunk，startPage == endPage</p>
         */
        int startPage,
        int endPage,

        /**
         * Chunk 索引范围（起止）
         * <p>用于追溯原始 chunk</p>
         */
        int startChunkIndex,
        int endChunkIndex,

        /**
         * 支持分数（rerank 分数的最大值）
         * <p>不是"置信度"，是相关性排序分数</p>
         */
        double supportScore,

        /**
         * 包含的 chunk 数量
         */
        int chunkCount,

        /**
         * 证据类型
         */
        EvidenceType type,

        /**
         * 原始 Document 列表（用于调试）
         */
        List<SourceChunk> sourceChunks

) {

    /**
     * 证据类型
     */
    public enum EvidenceType {
        /** 单个 chunk（未合并） */
        SINGLE,
        /** 多个连续 chunk 合并 */
        MERGED,
        /** 跨页合并 */
        CROSS_PAGE,
        /** OCR 内容 */
        OCR_CONTENT
    }

    /**
     * 源 chunk 信息（用于追溯）
     */
    @Builder
    public record SourceChunk(
            String chunkId,
            int chunkIndex,
            int pageIndex,
            double rerankScore,
            int charOffset,
            int charLength
    ) {}

    /**
     * 获取页码显示字符串
     */
    public String getPageDisplay() {
        if (startPage == endPage) {
            return "第" + startPage + "页";
        } else if (startPage > 0 && endPage > 0) {
            return "第" + startPage + "-" + endPage + "页";
        } else {
            return "";
        }
    }

    /**
     * 获取来源引用字符串
     */
    public String getSourceReference() {
        StringBuilder sb = new StringBuilder();
        sb.append(filename);
        String pageDisplay = getPageDisplay();
        if (!pageDisplay.isEmpty()) {
            sb.append(" | ").append(pageDisplay);
        }
        return sb.toString();
    }

    /**
     * 是否为 OCR 内容
     */
    public boolean isOcrContent() {
        return type == EvidenceType.OCR_CONTENT ||
               (sourceChunks != null && !sourceChunks.isEmpty() && sourceChunks.get(0).pageIndex() < 0);
    }
}
