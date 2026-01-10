# ADR-003: RAG 逻辑的分层与解耦 (Refactoring RAG Logic)

* **状态**: 已采纳 (Accepted)
* **日期**: 2026-01-10
* **决策人**: AgentHub 架构组
* **关联文档**: ADR-002 (RAG as a Tool)

---

## 1. 背景 (Context)

在实施 **ADR-002 (RAG as a Tool)** 的过程中，我们面临着如何处理现有代码 (`RagChatServiceImpl` 和 `VectorStoreHelper`) 的问题。

当前的现状是：
1.  **`RagChatServiceImpl`** 承担了过多职责：它是“上帝类”，既负责构建 `ChatClient`，又通过 `RerankerQuestionAnswerAdvisor` 硬编码了检索逻辑，还负责处理 DeepSeek 的流式响应。
2.  **`VectorStoreHelper`** 功能完备：它已经包含了高质量的文档切分（PDF/OCR/中文优化）和带权限过滤的检索方法 `searchWithUserFilter`。

如果不进行合理的分层重构，直接添加 Tool 会导致逻辑重复（Tool 一套检索，Advisor 一套检索）或者代码混乱。

## 2. 备选方案 (Options Considered)

*   **方案 A: 废弃 `VectorStoreHelper`，全移入 Service**
    *   *思路*：将 `VectorStoreHelper` 的代码全部剪切到新的 `PowerKnowledgeService` 中。
    *   *缺点*：`VectorStoreHelper` 包含大量底层的 PDF 解析、Tika 调用和 PGVector 操作，这些是通用的基础设施代码，不应该污染业务层的 Service。

*   **方案 B: 保持 Advisor 模式，只在需要时调用 Tool**
    *   *思路*：保留 `RagChatServiceImpl` 原样，只是额外增加 Tool。
    *   *缺点*：会导致 Agent 产生“双重人格”。Spring AI 的 Advisor 会在每次对话时强制检索（Pipeline 模式），而 Tool 也会检索。这会造成资源浪费和上下文冲突。

*   **方案 C: 分层重构 (Layered Refactoring)**
    *   *思路*：
        1.  **基础设施层**: 保留 `VectorStoreHelper`，专注做“脏活”（文档解析、向量I/O）。
        2.  **领域服务层**: 新增 `PowerKnowledgeService`，作为 Tool 的业务载体，调用 `VectorStoreHelper`。
        3.  **编排层**: 改造 `RagChatServiceImpl`，移除 Advisor，改为注册 Tool。

## 3. 决策 (Decision)

**我们选择 方案 C (分层重构)。**

具体的架构分层定义如下：

### 3.1 Infrastructure Layer (基础设施层)
*   **组件**: `VectorStoreHelper`
*   **职责**:
    *   处理 PDF/OCR 解析。
    *   执行中文分词与切片。
    *   直接操作 `VectorStore` (PGVector)。
    *   提供原子化的检索方法 `searchWithUserFilter(userId, query, ...)`。
*   **变动**: **保持不变**，它是整个 RAG 的基石。

### 3.2 Domain Service Layer (领域服务层)
*   **组件**: `PowerKnowledgeService` (新增)
*   **职责**:
    *   定义检索策略（如：默认 TopK 是多少，是否需要 Rerank）。
    *   适配 `PowerKnowledgeQuery` (Record)。
    *   调用 `VectorStoreHelper` 获取原始文档。
    *   组装 `PowerKnowledgeResult` (Record) 返回给 AI。
*   **定位**: 它是 AI Tool 的**实际执行者**。

### 3.3 Orchestration Layer (编排层)
*   **组件**: `RagChatServiceImpl` (改造)
*   **职责**:
    *   会话管理 (Session)。
    *   Token 消耗统计。
    *   **关键变更**: 移除 `RerankerQuestionAnswerAdvisor`。
    *   **关键变更**: 通过 `.functions("powerKnowledgeTool")` 赋予 ChatClient 检索能力。

## 4. 决策理由 (Rationale)

1.  **复用性最大化**：`VectorStoreHelper` 作为工具类，未来可以被文件上传接口调用，也可以被后台管理任务调用，不仅限于 RAG。
2.  **关注点分离**：`RagChatServiceImpl` 不再关心“怎么检索”，只关心“怎么和 LLM 聊天”。检索的细节被封装在 `PowerKnowledgeService` 中。
3.  **平滑迁移**：这种方式不需要重写复杂的 PDF 解析逻辑，只需重新“连线”即可。

## 5. 迁移路径 (Migration Path)

1.  创建 `PowerKnowledgeService`，注入 `VectorStoreHelper`。
2.  创建 `KnowledgeToolConfig`，注册 Bean。
3.  修改 `RagChatServiceImpl`，替换 Advisor 为 Tool Function。

通过此决策，我们将从“基于规则的检索系统”平稳过渡到“基于 Agent 的智能检索系统”。
