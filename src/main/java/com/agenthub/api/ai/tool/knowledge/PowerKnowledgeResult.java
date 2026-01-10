package com.agenthub.api.ai.tool.knowledge;


import java.util.List;
import java.util.Map;

public record PowerKnowledgeResult(
        String answer,
        List<String> rawContentSnippets,// 原始文档片段（用于 RAG 上下文）
        List<String> sourceNames,       // 来源文件（用于溯源）
        Map<String, Object> debugInfo   // 调试信息（耗时、分数）
) {
}
