package com.agenthub.api.ai.domain.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索结果
 *
 * <h3>v2.0 升级：</h3>
 * <ul>
 *   <li>新增 {@link EvidenceBlock} - 语义完整的证据块</li>
 *   <li>保留 {@link #rawContentSnippets} - 向后兼容</li>
 *   <li>保留 {@link #sources} - 来源文件列表</li>
 * </ul>
 *
 * @author AgentHub
 * @since 2026-02-07
 */
public record PowerKnowledgeResult(
        /** 简要答案摘要（给人类看的） */
        String answer,

        /** 原始文档片段（用于 RAG 上下文，向后兼容） */
        List<String> rawContentSnippets,

        /** 来源文件（用于溯源） */
        List<SourceDocument> sources,

        /** 调试信息（耗时、分数） */
        Map<String, Object> debugInfo,

        /** ========== v2.0 新增 ========== */

        /** 证据块列表（语义完整、可引用的证据单元） */
        List<EvidenceBlock> evidenceBlocks
) {
    // 💡【v1.0】内部定义一个微型 Record，专门承载文件信息
    public record SourceDocument(
            String filename,     // 文件名，如 "2026规则.pdf"
            String downloadUrl   // 下载链接，如 "http://oss.../file.pdf?token=..."
    ) {}

    /**
     * 创建 v1.0 风格的结果（向后兼容）
     */
    public static PowerKnowledgeResult v1(
            String answer,
            List<String> rawContentSnippets,
            List<SourceDocument> sources,
            Map<String, Object> debugInfo) {
        return new PowerKnowledgeResult(
                answer,
                rawContentSnippets,
                sources,
                debugInfo,
                List.of()  // v1.0 没有 evidenceBlocks
        );
    }

    /**
     * 创建 v2.0 格式的结果（带证据块）
     */
    public static PowerKnowledgeResult v2(
            String answer,
            List<String> rawContentSnippets,
            List<SourceDocument> sources,
            Map<String, Object> debugInfo,
            List<EvidenceBlock> evidenceBlocks) {
        return new PowerKnowledgeResult(
                answer,
                rawContentSnippets,
                sources,
                debugInfo,
                evidenceBlocks
        );
    }

    /**
     * 是否有证据块
     */
    public boolean hasEvidenceBlocks() {
        return evidenceBlocks != null && !evidenceBlocks.isEmpty();
    }

    /**
     * 获取证据块总数
     */
    public int getEvidenceBlockCount() {
        return evidenceBlocks != null ? evidenceBlocks.size() : 0;
    }

    /**
     * 是否有有效内容（用于缓存判断）
     */
    public boolean hasContent() {
        // 有答案或有证据块或有原始片段
        return (answer != null && !answer.isEmpty())
                || hasEvidenceBlocks()
                || (rawContentSnippets != null && !rawContentSnippets.isEmpty());
    }
}
