# 项目开发进度日报 (2026-01-18)

**日期**: 2026年1月18日
**模块**: AI Core / Memory / Architecture
**状态**: ✅ 已完成 (Critical Bugs Fixed & Architecture Upgraded)

---

## 1. 核心问题解决 (Troubleshooting)

### 1.1 会话 ID 校验异常
*   **现象**: 前端传空字符串 `""` 导致 `IllegalArgumentException`。
*   **原因**: `AIUnifiedController` 只校验了 `null`，未校验空串。
*   **解决**: 引入 `StringUtils.hasText()` 进行强校验，若为空则自动生成 UUID。

### 1.2 Redis 只读副本写入失败
*   **现象**: `RedisReadOnlyException`，导致 AI 无法保存记忆，对话中断。
*   **原因**: 本地开发环境连接的 Redis 是只读从节点，或集群配置不支持写入。
*   **解决**: **架构迁移**。废弃 Redis 存储，基于 PostgreSQL + MyBatis-Plus 实现了持久化的 `MybatisChatMemoryRepository`。

### 1.3 Jackson 反序列化多态异常
*   **现象**: 
    1. `InvalidDefinitionException`: 无法构造 `Message` 接口。
    2. `MismatchedInputException`: 无法构造 `AssistantMessage`。
    3. `ValueInstantiationException`: `Content must not be null`。
*   **原因**: 
    *   Spring AI 的 `Message` 是接口，Jackson 不知道转成哪个实现类。
    *   Spring AI M4 版本序列化字段 (`text`) 与构造函数字段 (`content`/`textContent`) 不一致。
    *   全局 `ObjectMapper` 未注册 Spring AI 的 MixIns。
*   **解决 (三层防护)**:
    1.  **独立 Mapper**: 在 Repository 中 `copy()` 了一个独立的 `ObjectMapper`，不影响全局。
    2.  **注册 MixIns**: 手动注册 `UserMessageMixIn` 等，教 Jackson 识别构造函数。
    3.  **字段补全**: 手动拦截 JSON，将 `text` 字段的值复制给 `content` 和 `textContent`，消除版本差异。

---

## 2. 架构升级 (Architecture Upgrade)

### 2.1 统一会话记忆 (Unified Session Memory)
*   **目标**: 让 Chat (问答) 能感知到 Audit (审查) 和 Calc (计算) 的结果。
*   **实现**:
    *   **MemorySyncService**: 一个“领域驱动”的翻译服务，能将 `ComplianceReport` 和 `WorkerResult` 解析为包含“风险详情”、“计算公式”的自然语言摘要。
    *   **RouterService 切面**: 在 UseCase 执行完毕后 (`whenComplete`)，自动触发记忆同步。

### 2.2 异步优化 (Async Optimization)
*   **机制**: 采用 **Callback (回调) + ThreadPool (线程池)** 双重异步。
*   **效果**: `Router` 派发任务后立即返回，不阻塞 HTTP 线程；记忆同步操作在 `taskExecutor` 独立线程池中执行，数据库 I/O 零延迟感。

### 2.3 思考过程透传 (Reasoning Streaming)
*   **升级**: `ChatUseCase` 从返回纯文本流升级为 **JSON 对象流** (`StreamChunk`)。
*   **收益**: 支持透传 DeepSeek R1 等推理模型的 `reasoning_content`，前端可展示“AI 正在思考...”。

---

## 3. 重要知识点 (Key Learnings)

### 3.1 Java 语法与并发
*   **`yield` 关键字**: 在 `switch` 表达式中，它是“返回值”的意思（类似 `return`），与多线程的 `Thread.yield()`（让出 CPU）毫无关系。
*   **CompletableFuture**: `whenComplete` 是注册一个回调，不会阻塞当前线程。
*   **变量捕获**: Lambda 表达式可以捕获当时的 `sessionId`，即使异步执行，上下文依然正确。

### 3.2 架构设计原则
*   **角色分离**: 
    *   `ChatUseCase` (咨询师) **不直接调用** `Workflow` (施工队)。
    *   它们通过 `Router` (分诊台) 分流，通过 `Memory` (病历本) 共享信息。
*   **单例模式**: Spring 的 `@Repository` 默认是单例的，所以在构造函数里创建的独立 `ObjectMapper` 也是单例复用的，无性能损耗。

---

## 4. 下一步计划 (Next Steps)

*   **前端适配**: 推进前端完成 JSON 流式数据的解析逻辑（`JSON.parse`）。
*   **业务验证**: 测试“先审查、后问答”的跨上下文场景，验证 Chat 是否真的变聪明了。
