package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.knowledge.EvidenceBlock;
import com.agenthub.api.ai.domain.knowledge.EvidenceBlock.SourceChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 证据组装器（Evidence Assembly）
 * <p>将 rerank 后的 Document（chunk）组装成语义完整的 EvidenceBlock</p>
 *
 * <h3>核心原则：</h3>
 * <ul>
 *   <li>不摘要、不压缩、不改写原文</li>
 *   <li>同一来源且页码相邻的 chunk 可以合并</li>
 *   <li>保留完整的证据链（来源 + 页码 + chunk范围）</li>
 * </ul>
 *
 * <h3>组装规则：</h3>
 * <pre>
 * 同一组合并条件：
 *   1. 相同 filename
 *   2. pageIndex 相邻（±1）
 *   3. chunkIndex 连续
 *
 * 不同组不合并：
 *   1. 不同文件
 *   2. 页码差距 > 2
 *   3. chunkIndex 不连续
 * </pre>
 *
 * @author AgentHub
 * @since 2026-02-07
 */
@Slf4j
@Component
public class EvidenceAssembly {

    /**
     * 页码相邻的最大允许间隔
     * <p>跨页时可能会有跳页（如图片页），允许 ±2</p>
     */
    private static final int MAX_PAGE_GAP = 2;

    /**
     * 将 rerank 后的 Document 列表组装成 EvidenceBlock 列表
     *
     * @param documents rerank 后的 Document 列表（已按 rerank_score 排序）
     * @return EvidenceBlock 列表
     */
    public List<EvidenceBlock> assemble(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        log.debug("[EvidenceAssembly] 开始组装，输入文档数: {}", documents.size());

        // 1. 按 filename 分组
        Map<String, List<DocumentWithMeta>> byFile = groupByFile(documents);

        // 2. 每个文件内进行相邻合并
        List<EvidenceBlock> blocks = new ArrayList<>();
        for (Map.Entry<String, List<DocumentWithMeta>> entry : byFile.entrySet()) {
            blocks.addAll(mergeAdjacentChunks(entry.getValue()));
        }

        // 3. 按 supportScore 重新排序（保持 rerank 的顺序）
        blocks.sort((a, b) -> Double.compare(b.supportScore(), a.supportScore()));

        log.debug("[EvidenceAssembly] 组装完成，输出 EvidenceBlock 数: {}", blocks.size());

        return blocks;
    }

    /**
     * 按 filename 分组
     */
    private Map<String, List<DocumentWithMeta>> groupByFile(List<Document> documents) {
        Map<String, List<DocumentWithMeta>> grouped = new HashMap<>();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            DocumentWithMeta meta = extractMetadata(doc, i);
            grouped.computeIfAbsent(meta.filename(), k -> new ArrayList<>()).add(meta);
        }

        return grouped;
    }

    /**
     * 提取 Document 的 metadata
     */
    private DocumentWithMeta extractMetadata(Document doc, int rerankRank) {
        String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
        Integer pageIndex = toInt(doc.getMetadata().get("page_index"));
        Integer chunkIndex = toInt(doc.getMetadata().get("chunk_index"));
        Double rerankScore = toDouble(doc.getMetadata().get("rerank_score"));
        Boolean ocrUsed = toBoolean(doc.getMetadata().get("ocr_used"));
        String internalId = doc.getMetadata().getOrDefault("internal_id", "").toString();

        return new DocumentWithMeta(
                doc,
                filename,
                pageIndex != null ? pageIndex : -1,
                chunkIndex != null ? chunkIndex : 0,
                rerankScore != null ? rerankScore : 0.0,
                ocrUsed != null && ocrUsed,
                internalId,
                rerankRank
        );
    }

    /**
     * 合并相邻的 chunk
     * <p>同一文件内，页码相邻且 chunkIndex 连续的可以合并</p>
     */
    private List<EvidenceBlock> mergeAdjacentChunks(List<DocumentWithMeta> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        // 先按 pageIndex + chunkIndex 排序（确保顺序正确）
        chunks.sort(Comparator
                .comparing(DocumentWithMeta::pageIndex)
                .thenComparing(DocumentWithMeta::chunkIndex));

        List<EvidenceBlock> blocks = new ArrayList<>();

        int start = 0;
        for (int i = 1; i <= chunks.size(); i++) {
            // 最后一个或需要断开
            if (i == chunks.size() || shouldSplit(chunks.get(i - 1), chunks.get(i))) {
                // 从 start 到 i-1 是一组
                blocks.addAll(createBlock(chunks.subList(start, i)));
                start = i;
            }
        }

        return blocks;
    }

    /**
     * 判断两个 chunk 是否应该断开（不合并）
     */
    private boolean shouldSplit(DocumentWithMeta prev, DocumentWithMeta curr) {
        // 1. chunkIndex 不连续 → 断开
        int chunkGap = curr.chunkIndex() - prev.chunkIndex();
        if (chunkGap > 1) {
            log.trace("[EvidenceAssembly] chunk 不连续: {} -> {}, gap={}",
                    prev.chunkIndex(), curr.chunkIndex(), chunkGap);
            return true;
        }

        // 2. 页码差距过大 → 断开
        if (prev.pageIndex() >= 0 && curr.pageIndex() >= 0) {
            int pageGap = Math.abs(curr.pageIndex() - prev.pageIndex());
            if (pageGap > MAX_PAGE_GAP) {
                log.trace("[EvidenceAssembly] 页码差距过大: {} -> {}, gap={}",
                        prev.pageIndex(), curr.pageIndex(), pageGap);
                return true;
            }
        }

        // 3. 其他情况：可以合并
        return false;
    }

    /**
     * 创建 EvidenceBlock
     * <p>单个 chunk 或多个合并的 chunk 都走这里</p>
     */
    private List<EvidenceBlock> createBlock(List<DocumentWithMeta> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        if (chunks.size() == 1) {
            // 单个 chunk
            DocumentWithMeta c = chunks.get(0);
            return List.of(EvidenceBlock.builder()
                    .content(c.document().getText())
                    .filename(c.filename())
                    .startPage(c.pageIndex())
                    .endPage(c.pageIndex())
                    .startChunkIndex(c.chunkIndex())
                    .endChunkIndex(c.chunkIndex())
                    .supportScore(c.rerankScore())
                    .chunkCount(1)
                    .type(c.ocrUsed() ? EvidenceBlock.EvidenceType.OCR_CONTENT : EvidenceBlock.EvidenceType.SINGLE)
                    .sourceChunks(List.of(createSourceChunk(c)))
                    .build());
        }

        // 多个 chunk 合并
        String filename = chunks.get(0).filename();
        int minPage = chunks.stream().mapToInt(DocumentWithMeta::pageIndex).min().orElse(-1);
        int maxPage = chunks.stream().mapToInt(DocumentWithMeta::pageIndex).max().orElse(-1);
        int minChunk = chunks.stream().mapToInt(DocumentWithMeta::chunkIndex).min().orElse(0);
        int maxChunk = chunks.stream().mapToInt(DocumentWithMeta::chunkIndex).max().orElse(0);
        double maxScore = chunks.stream().mapToDouble(DocumentWithMeta::rerankScore).max().orElse(0.0);

        // 拼接内容
        StringBuilder content = new StringBuilder();
        List<SourceChunk> sourceChunks = new ArrayList<>();
        int charOffset = 0;
        boolean hasOcr = false;

        for (DocumentWithMeta c : chunks) {
            String text = c.document().getText();
            content.append(text);

            int charLen = text.length();
            sourceChunks.add(createSourceChunk(c, charOffset, charLen));
            charOffset += charLen;

            if (c.ocrUsed()) {
                hasOcr = true;
            }
        }

        // 判断类型
        EvidenceBlock.EvidenceType type;
        if (hasOcr) {
            type = EvidenceBlock.EvidenceType.OCR_CONTENT;
        } else if (minPage != maxPage) {
            type = EvidenceBlock.EvidenceType.CROSS_PAGE;
        } else {
            type = EvidenceBlock.EvidenceType.MERGED;
        }

        return List.of(EvidenceBlock.builder()
                .content(content.toString())
                .filename(filename)
                .startPage(minPage > 0 ? minPage : -1)
                .endPage(maxPage > 0 ? maxPage : -1)
                .startChunkIndex(minChunk)
                .endChunkIndex(maxChunk)
                .supportScore(maxScore)
                .chunkCount(chunks.size())
                .type(type)
                .sourceChunks(sourceChunks)
                .build());
    }

    /**
     * 创建 SourceChunk
     */
    private SourceChunk createSourceChunk(DocumentWithMeta meta) {
        return createSourceChunk(meta, 0, meta.document().getText().length());
    }

    /**
     * 创建 SourceChunk（带偏移量）
     */
    private SourceChunk createSourceChunk(DocumentWithMeta meta, int charOffset, int charLength) {
        return SourceChunk.builder()
                .chunkId(meta.internalId())
                .chunkIndex(meta.chunkIndex())
                .pageIndex(meta.pageIndex())
                .rerankScore(meta.rerankScore())
                .charOffset(charOffset)
                .charLength(charLength)
                .build();
    }

    // ==================== 辅助方法 ====================

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }

    // ==================== 内部类 ====================

    /**
     * 带 metadata 的 Document
     */
    private record DocumentWithMeta(
            Document document,
            String filename,
            int pageIndex,
            int chunkIndex,
            double rerankScore,
            boolean ocrUsed,
            String internalId,
            int rerankRank
    ) {}
}
