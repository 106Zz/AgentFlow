package com.agenthub.api.ai.tool.knowledge;


import java.util.List;
import java.util.Map;

public record PowerKnowledgeResult(
        String answer,
        List<String> rawContentSnippets,// 原始文档片段（用于 RAG 上下文）
        // 🚨 修改点：从 List<String> 变为 List<SourceDocument>
        List<SourceDocument> sources,       // 来源文件（用于溯源）
        Map<String, Object> debugInfo   // 调试信息（耗时、分数）
) {
    // 💡【新增】内部定义一个微型 Record，专门承载文件信息
    public record SourceDocument(
            String filename,     // 文件名，如 "2026规则.pdf"
            String downloadUrl   // 下载链接，如 "http://oss.../file.pdf?token=..."
    ) {}
}
