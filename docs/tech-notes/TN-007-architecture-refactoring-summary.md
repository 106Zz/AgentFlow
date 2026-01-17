# TN-007: 架构重构总结报告 (Architecture Refactoring Summary)

* **状态** : 已完成 (Completed)
* **日期** : 2026-01-17
* **模块** : AI Core / Architecture
* **主题** : 从 Service 模式迁移至 UseCase 策略模式的复盘与总结

## 1. 核心目标
将旧的、耦合严重的 `Service` 模式（RagChatService + ComplianceWorkflowService）迁移到更灵活、可扩展的 **UseCase 策略模式**，同时**找回丢失的高级特性**（记忆、提示词、流式输出）。

## 2. 代码变更概览 (Code Changes)

| 文件/组件 | 修改前状态 | 修改后状态 | 核心逻辑变动 |
| :--- | :--- | :--- | :--- |
| **`AIRequest.java`** | 只有 `userId`, `query` | 新增 **`sessionId`** | 支持多轮对话的会话隔离（像 ChatGPT 一样）。 |
| **`AIResponse.java`** | 只有 `asyncData` (一次性) | 新增 **`streamData` (Flux)** | 支持流式打字机效果。容器能装流了。 |
| **`ChatUseCase.java`** | 只有简单的 `return null` | **全功能 RAG 引擎** | 1. 动态构建带记忆/人设的 `SmartClient`。<br>2. 实现了 `.stream()` 流式调用。<br>3. 取代了旧 Service。 |
| **`RouterService.java`** | 杂乱调用各路 Service | **纯净的分发器** | 只负责识别意图 (`AUDIT/CALC/CHAT`)，然后派单给对应的 `UseCase`。 |
| **`RagChatServiceImpl.java`** | 负责聊天逻辑 | **(建议删除)** | 逻辑已完全迁移至 `ChatUseCase`，完成历史使命。 |
| **TN-006 文档** | (不存在) | **新增架构文档** | 记录了“记忆断层”问题和未来的统一记忆方案。 |

## 3. 核心知识点回顾 (Key Learnings)

### 3.1 架构思维：Builder 模式 vs 单例模式
*   **疑问**: “到处都用 `ChatClient`，不会慢吗？”
*   **顿悟**: `ChatClient` 不是一个忙碌的工人，而是一个**“厨房”**。
    *   在 `Skill` 里，我们用它做“快餐”（无状态、快速判断）。
    *   在 `ChatUseCase` 里，我们用它做“宴席”（有记忆、有人设、流式上菜）。
    *   **结论**: 同一个底层模型，可以通过不同的配置（Advisor/Prompt）构建出完全不同用途的 Client。

### 3.2 架构思维：策略模式 (Strategy Pattern)
*   **疑问**: “为什么要把 RagChatService 拆掉？”
*   **顿悟**: 旧架构是“一团乱麻”，所有逻辑都挤在一起。新架构是**“专人专事”**：
    *   `AUDIT` -> `AuditUseCase` (出报告)
    *   `CALC` -> `CalcUseCase` (出数据)
    *   `CHAT` -> `ChatUseCase` (出对话)
    *   `Router` -> 只管分发，不管怎么做。

### 3.3 产品体验：记忆与会话 (Memory & Session)
*   **疑问**: “如果不闲聊，是不是就没记忆了？”
*   **顿悟**: 发现了**“记忆断层”**。
    *   用户做完 `AUDIT` 后，记忆库是空的。
    *   **解决方案**: 未来需要在 Router 层做一个“记忆切面”，不管做什么任务，都把结果摘要存进记忆库，打通上下文（详见 TN-006）。
*   **技术点**: 学会了用 `sessionId` 而不是 `userId` 来隔离对话，避免记忆串味。

### 3.4 技术实现：流式与异步 (Stream vs Async)
*   **疑问**: “流式去哪了？”
*   **顿悟**: 流式 (`Flux`) 和 异步 (`CompletableFuture`) 是两码事。
    *   我们通过改造 `AIResponse`，让它既能装“整箱货”（Future），也能装“水管”（Flux），实现了接口的统一。

## 4. 疑问与解决方式复盘 (Q&A Recap)

| 你的疑问 | 我们的解答 | 最终方案 |
| :--- | :--- | :--- |
| **Q1: 为什么 ChatClient 到处用？** | 它只是工具，多线程调用互不干扰，完全没问题。 | 在 Skill 里用基础版，在 UseCase 里用满配版。 |
| **Q2: 我的 Prompt 和记忆去哪了？** | 它们之前绑在旧 Service 上，现在需要搬家。 | 在 `ChatUseCase` 里通过 `buildSmartClient` 找回了它们。 |
| **Q3: 最后的输出是谁？** | 看意图。AUDIT 输出报告，CHAT 输出对话。 | `AUDIT` 不需要流式总结，`CHAT` 才需要。 |
| **Q4: 怎么做流式？** | 修改容器 (`AIResponse`) 和 逻辑 (`UseCase`)。 | `ChatUseCase` 改为 `.stream()`，`AIResponse` 增加 `Flux` 字段。 |
| **Q5: 会话ID用什么？** | 业界标准是用 UUID 做 SessionId。 | 修改 `AIRequest` 增加 `sessionId` 字段。 |

## 5. 检查清单 (Checklist)

你可以按照这个清单检查你的代码：

- [x] **Check 1**: 打开 `ChatUseCase.java`，确认里面有 `buildSmartClient` 方法，并且用了 `request.sessionId()`。
- [x] **Check 2**: 打开 `AIResponse.java`，确认里面有了 `streamData` 字段。
- [x] **Check 3**: 打开 `RouterService.java`，确认它现在很干净，没有乱七八糟的 Service 注入。
- [ ] **Check 4 (待做)**: 找到调用 `RouterService` 的那个 **Controller**，修改它，让它处理 `AIResponse.Type.STREAM`，把 Flux 透传给前端。
