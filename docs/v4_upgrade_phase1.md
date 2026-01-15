# AgentHub v4.0 升级指南 - Phase 1: 基础设施改造 (已完成)

本指南详细记录了如何对底层基础设施进行改造，以支持 **自动化元数据打标 (Automated Metadata Tagging)** 和 **条件检索 (Filtered Retrieval)**。

---

## 1. [数据入库层] 自动化分类打标 (Ingestion)
**文件路径**: `src/main/java/com/agenthub/api/ai/utils/VectorStoreHelper.java`

**逻辑说明**: 
通过文件名关键字，在文档入库阶段自动识别业务类别并写入 Vector DB 的 Metadata。

### 核心实现：
1. **分类规则**：
   - `BUSINESS`: 涉及结算、价格、费用、合约、零售等。
   - `TECHNICAL`: 涉及技术、参数、标准、负荷曲线、新能源、虚拟电厂等。
   - `REGULATION`: 政策、通知、办法、细则等（兜底分类）。

2. **代码逻辑** (已集成在 `processAndStore` 中):
```java
String category = inferCategoryFromFilename(filename);
chunk.getMetadata().put("category", category);
log.info("文件 [{}] 被自动归类为: [{}]", filename, category);
```

---

## 2. [检索工具层] 条件过滤检索 (Retrieval)
**文件路径**: `src/main/java/com/agenthub/api/ai/utils/VectorStoreHelper.java`

**修改目标**: 扩展 `searchWithUserFilter` 方法，支持 `category` 过滤。

### 代码实现：
```java
public List<Document> searchWithUserFilter(String query, Long userId, boolean isAdmin,
                                           int topK, double threshold, String category) {
    String filterExpression = "";
    // 1. 构建基础权限过滤...
    // 2. 叠加类别过滤 (Category Filter)
    if (category != null && !category.isBlank()) {
        String categoryFilter = String.format("category == '%s'", category);
        filterExpression = filterExpression.isEmpty() ? categoryFilter : 
                           String.format("(%s) && %s", filterExpression, categoryFilter);
    }
    // 3. 执行 similaritySearch...
}
```

---

## 3. [输入定义层] 改造 `PowerKnowledgeQuery`
**文件路径**: `src/main/java/com/agenthub/api/ai/tool/knowledge/PowerKnowledgeQuery.java`

**修改目标**: 增加 `category` 字段，使 AI 能够显式请求特定类别的知识。

```java
public record PowerKnowledgeQuery(
        String query,
        Integer topK,
        String yearFilter,
        @JsonPropertyDescription("业务分类: BUSINESS, TECHNICAL, REGULATION") 
        String category 
) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PowerKnowledgeQuery fromString(String query) {
        return new PowerKnowledgeQuery(query, null, null, null);
    }
}
```

---

## 4. [业务分层] 改造 `PowerKnowledgeService`
**文件路径**: `src/main/java/com/agenthub/api/ai/service/PowerKnowledgeService.java`

**修改目标**: 透传 `category` 参数，确保 RAG 链路闭环。

```java
List<Document> rawDocs = vectorStoreHelper.searchWithUserFilter(
        query.query(), userId, isAdmin, recallCount, 0.45, 
        query.category() // 关键透传
);
```

---

## 💡 下一阶段预告：Phase 2 - Skill (技能层)
基础设施就绪后，我们将利用 `category` 参数创建专门的 **Skill**：
- `ComplianceSkills.verifyTechnical()` -> 强制使用 `category='TECHNICAL'`
- `ComplianceSkills.verifyCommercial()` -> 强制使用 `category='BUSINESS'`

这样可以彻底杜绝 AI “指鹿为马”（用技术规范去回答商务问题）的幻觉风险。