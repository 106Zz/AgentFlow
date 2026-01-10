# TN-001: RAG 检索结果的数据结构与血缘追踪 (Data Lineage)

* **日期**: 2026-01-10
* **模块**: AI Core / PowerKnowledgeService
* **涉及组件**: `VectorStoreHelper`, `PowerKnowledgeResult`

---

## 1. 核心定义 (The Contract)

在 AgentHub 的 RAG 链路中，Tool 返回给大模型的数据结构由 `PowerKnowledgeResult` 严格定义。
该结构的设计目的是**将底层的非结构化向量数据，转换为 AI 可理解的结构化情报**。

### 1.1 数据结构定义
```java
package com.agenthub.api.ai.tool.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 结构说明：
 * answer: 工具对检索内容的初步整合
 * rawContentSnippets: 原始片段，保证 LLM 能看到未被改动的事实
 * sourceNames: 去重后的来源，用于前端展示引用来源
 * debugInfo: 监控检索质量（如分值）
 */
public record PowerKnowledgeResult(
        String answer,                  
        List<String> rawContentSnippets,
        List<String> sourceNames,       
        Map<String, Object> debugInfo   
) {}
```

---

## 2. 数据血缘 (Data Lineage)

我们需要回答一个核心问题：**`sourceNames` 和 `debugInfo` 中的数据到底是从哪里来的？**

### 2.1 全链路数据流转图

```mermaid
graph TD
    User[用户上传文件] -->|文件名: rule.pdf| Helper[VectorStoreHelper]
    
    subgraph "基础设施层 (Infrastructure)"
        Helper -->|1. 切片| Chunks[文档切片 List<Document>]
        Helper -->|2. 注入 Metadata| Meta[put("filename", "rule.pdf")]
        Meta -->|3. 存入| DB[(PGVector 数据库)]
    end
    
    subgraph "服务层 (Service)"
        DB -->|4. 检索| RawDocs[原始 Document 列表]
        RawDocs -->|5. 提取 Metadata| Service[PowerKnowledgeService]
        Service -->|6. 去重 (.distinct)| Sources[List<String> sources]
    end
    
    Service -->|7. 组装| Result[PowerKnowledgeResult]
```

### 2.2 溯源分析：Filename 是何时注入的？

元数据（Metadata）并非数据库自动生成，而是我们在**文件入库（Ingestion）阶段**显式注入的。

**代码位置**: `com.agenthub.api.ai.utils.VectorStoreHelper`

```java
// VectorStoreHelper.java 核心代码片段
public int processAndStore(...) {
    // ... 切片逻辑 ...
    
    for (int i = 0; i < chunks.size(); i++) {
        Document chunk = chunks.get(i);
        
        // [关键操作] 在这里将文件名“烙印”进每一个切片中
        chunk.getMetadata().put("filename", filename); 
        
        // 其他元数据
        chunk.getMetadata().put("user_id", userId);
        chunk.getMetadata().put("chunkIndex", i);
    }
    
    vectorStore.add(chunks); // 存入数据库
}
```

**原理**: 当 `Document` 被存入 PGVector 时，`metadata` Map 会被序列化为 JSON 格式存储在数据库的一列中。检索出来时，Spring AI 会自动将其反序列化。

---

## 3. 业务逻辑实现 (Implementation Details)

在 `PowerKnowledgeService` 中，我们需要将数据库查出来的 `List<Document>` 转换为 `PowerKnowledgeResult`。

### 3.1 为什么需要去重 (Deduplication)？

**场景**: 用户询问“结算公式”。
**现象**: 数据库返回了 3 个切片（Chunks），它们可能连续分布在同一文件的第 10、11、12 页。

* Chunk A -> metadata: {filename: "2026规则.pdf", page: 10}
* Chunk B -> metadata: {filename: "2026规则.pdf", page: 11}
* Chunk C -> metadata: {filename: "2026规则.pdf", page: 12}

**处理逻辑**:
如果不去重，`sourceNames` 会变成 `["2026规则.pdf", "2026规则.pdf", "2026规则.pdf"]`，这是冗余信息。
我们使用 Java Stream API 的 `.distinct()` 方法，将其合并为唯一的 `["2026规则.pdf"]`。

### 3.2 最终组装代码实现方案

**代码位置**: `com.agenthub.api.ai.service.PowerKnowledgeService`

```java
// 1. 提取来源 (Map & Distinct)
List<String> sources = finalDocs.stream()
        .map(d -> d.getMetadata().getOrDefault("filename", "unknown").toString())
        .distinct() // <--- 核心去重逻辑
        .collect(Collectors.toList());

// 2. 提取最高分 (用于监测召回质量)
double maxScore = finalDocs.stream()
        .mapToDouble(d -> {
            Object s = d.getMetadata().get("rerank_score");
            return s != null ? Double.parseDouble(s.toString()) : 0.0;
        })
        .max()
        .orElse(0.0);

// 3. 封装返回
return new PowerKnowledgeResult(
        contentSummary,
        rawSnippets,
        sources,
        Map.of("max_score", maxScore)
);
```

---

## 4. 总结 (Summary)

1. **Metadata 来源**: 所有的元数据（filename, user_id, page）都是在 **VectorStoreHelper** 入库时手动 Put 进去的。
2. **数据闭环**: 入库时存入 Metadata -> 检索时取出 Metadata -> Service 层清洗 Metadata -> 返回给 Tool。
3. **开发规范**: 如果未来需要新增元数据（例如 `year` 年份过滤器），必须修改 `VectorStoreHelper` 的入库逻辑，否则数据库里没有该字段，过滤器将无法生效。
