# TN-004: 合规审查智能体与证据溯源架构 (v2.0)

* **日期** : 2026-01-13
* **状态** : 已完成 (Implemented)
* **模块** : AI Core / Compliance / Knowledge Base
* **标签** : `Nested Agent`, `OSS Integration`, `Data Lineage`

---

## 1. 背景与目标 (Context)

> **[与 v1.0 保持一致]**：我们确立了以“合规审查”为核心壁垒的战略，否决了通用的 WebSearch，转而深挖内部 41 份电力核心文档的价值。

在构建能源电力垂直领域 Agent 的过程中，为了增强用户（尤其是甲方）对 AI 结果的信任，单纯的文本引用已不足够。

**目标变更/升级**：

1. **架构升级**：延续 v1.0 的“嵌套智能体 (Nested Agent)”模式，在 Tool 内部进行强制性的规则检索与比对。
2. **证据溯源 (New)**：实现 **"Click-to-Download" (点击下载)** 能力。当 AI 引用某份红头文件时，用户必须能直接下载该文件的 PDF 原文进行核对。

## 2. 后端架构变更 (Backend Implementation)

### 2.1 数据契约升级 (Contract Evolution)

为了支持文件下载，`PowerKnowledgeResult` 的结构发生了破坏性变更 (Breaking Change)。

* **Before (v1.0)**: `List<String> sourceNames` (只存文件名)
* **After (v2.0)**: `List<SourceDocument> sources` (存对象：文件名 + 签名下载链接)

```java
// Definition: com.agenthub.api.ai.tool.knowledge.PowerKnowledgeResult
public record PowerKnowledgeResult(
    String answer,
    List<String> rawContentSnippets,
    List<SourceDocument> sources, // <--- CHANGED
    Map<String, Object> debugInfo
) {
    public record SourceDocument(
        String filename,     // e.g. "2026广东电力交易规则.pdf"
        String downloadUrl   // e.g. "https://oss.../file.pdf?token=..."
    ) {}
}
```

### 2.2 核心服务逻辑 (Service Logic)

`PowerKnowledgeService` 集成了阿里云 OSS SDK。在每次检索时，系统会动态生成 **有效期 1 小时** 的临时访问链接 (Presigned URL)。

* **安全机制**：OSS Bucket 保持私有 (Private)，不开放公网读权限。
* **生成时机**：仅在 RAG 检索命中该文档时生成。

### 2.3 合规审查服务适配 (Compliance Service)

> **[与 v1.0 保持一致]**：`ComplianceService` 依然使用内部独立的 `ChatClient` 执行比对任务，并强制调用 `PowerKnowledgeService` 获取证据。

**v2.0 更新**：Prompt 逻辑已适配新的数据结构，确保 AI 知晓参考文件的完整元数据，并继续通过 `BeanOutputConverter` 强制输出 JSON 格式。

```java
// Prompt 模板结构 (已适配 v2.0)
String prompt = """
    【参考文件列表】：%s  <-- 传入 sources (包含文件名与下载链接)
    【权威规则摘要】：%s
    【用户待审内容】：%s
    
    请判断... (JSON Schema 约束) ...
    %s 
    """;
```

## 3. 前端集成指南 (Frontend Integration Guide) —— **NEW**

> **To: 前端开发组**
> 接口 `POST /api/chat` 返回的 Tool 调用结果结构已变更，请按以下规范适配 UI。

### 3.1 接口响应示例 (JSON Response)

当 Agent 调用工具时，返回结果将包含带下载链接的 `sources` 数组：

```json
{
  "type": "tool_call",
  "tool_name": "powerKnowledgeTool",
  "result": {
    "answer": "根据2026年规则，偏差考核免责范围已调整...",
    "sources": [
      {
        "filename": "省内|2026|广东电力市场交易基本规则.pdf",
        "downloadUrl": "https://oss-cn-shanghai.aliyuncs.com/agenthub/xxx.pdf?Expires=17000&..."
      }
    ]
  }
}
```

### 3.2 UI 渲染规范 (UI Specification)

请在聊天气泡下方增加 **"参考依据 (References)"** 区域：

1. **卡片展示**：遍历 `sources` 数组，渲染为可点击的文件卡片。
2. **点击行为**：使用 `target="_blank"` 触发下载或预览。
3. **过期处理**：链接有效期 1 小时，失效后建议提示用户“重新提问”。

## 4. 架构决策理由 (Rationale)

### 4.1 为什么要给下载链接？ (Why Download?)

* **建立信任 (Trust)**：对于电力招投标场景，AI 只是辅助，红头文件原文才是法律依据。
* **数据隔离 (Privacy)**：使用 Presigned URL 保证了只有当前会话用户能通过临时授权访问私有文件。

### 4.2 为什么用 List<Object> 替换 List<String>？

* **可扩展性 (Extensibility)**：为未来添加 `pageNumber` (页码) 或 `fileSize` (文件大小) 留出空间。使用对象结构比单纯的字符串列表更具前瞻性。

---

**架构师备注 (Architect's Note)**：
此次升级完成了从“纯文本 RAG”到“可信证据链 RAG”的跨越。请确保 `application.yml` 中的 OSS 配置正确。
