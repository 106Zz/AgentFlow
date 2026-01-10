# ADR-002: 将 RAG 检索封装为独立工具 (RAG as a Tool)

* **状态**: 已采纳 (Accepted)
* **日期**: 2026-01-10
* **决策人**: AgentHub 架构组
* **关联文档**: ADR-001 (使用 Record)

---

## 1. 背景 (Context)

在 AgentHub 的初期版本中，RAG (检索增强生成) 逻辑通常是以 "Service" 的形式硬编码在业务流程中的。
例如，用户发起聊天 -> Controller 调用 `RagService` -> `RagService` 检索向量库 -> 拼接 Prompt -> 调用 LLM。

这种 **"Pipeline (流水线)"** 模式在处理简单问答时很有效，但在面对复杂场景（如 "先查A，再查B，最后汇总" 或 "根据条件判断是否需要查库"）时，显得僵化且难以扩展。

我们需要一种更灵活的机制，赋予 Agent **"自主决定是否检索、何时检索、检索什么"** 的能力。

## 2. 备选方案 (Options Considered)

我们评估了以下两种架构模式：

* **方案 A: 传统的 Service 编排模式 (Pipeline)**
    * 逻辑：`Controller` -> `RagService.retrieve()` -> `LlmService.chat()`
    * *优点*：实现简单，流程可控，延迟低。
    * *缺点*：Agent 处于被动地位。无论用户问什么，系统都会强制进行一次检索（或者需要写极其复杂的 `if-else` 逻辑来判断）。难以支持多步推理。

* **方案 B: 工具化模式 (Tool / Function Calling)**
    * 逻辑：将检索逻辑封装为 `PowerKnowledgeTool`，暴露给 LLM。
    * 流程：LLM (思考) -> 决定调用 Tool -> 执行检索 -> LLM (接收结果) -> 生成回答。
    * *优点*：
        *   **自主性**：Agent 可以根据语义判断是否需要查库（例如用户只是打招呼时，就不必查库）。
        *   **复用性**：同一个检索工具可以被不同的 Agent（如“客服Agent”、“审核Agent”）复用。
        *   **可解释性**：我们可以清楚地看到 Agent 为什么调用工具，以及调用了什么参数。

## 3. 决策 (Decision)

**我们将 RAG 检索能力封装为标准的 AI Tool (`PowerKnowledgeTool`)。**

具体实现规范：
1.  **位置**：`com.agenthub.api.ai.tool.knowledge`
2.  **输入**：使用 `PowerKnowledgeQuery` (Record) 定义查询参数，必须包含详尽的 `@Description` 以指导 LLM。
3.  **输出**：使用 `PowerKnowledgeResult` (Record) 返回事实数据和来源元数据，**不包含**总结或对话逻辑。
4.  **注册**：通过 Spring AI 的 `FunctionCallback` 机制将其注册到 `ChatClient` 中。

## 4. 决策理由 (Rationale)

### 4.1 认知卸载 (Cognitive Offloading)
将“如何检索”的复杂逻辑（向量搜索、重排序、阈值过滤）封装在 Tool 内部。Agent 只需要关注“意图识别”，即“通过自然语言描述来调用工具”。这降低了 Agent 的 Prompt 复杂度。

### 4.2 支持多跳推理 (Multi-hop Reasoning)
在复杂的电力业务场景中，一个问题可能需要查阅多份文档。
*   *Pipeline 模式*：很难实现“查完A文档，发现缺数据，再去查B文档”。
*   *Tool 模式*：Agent 可以在一次对话中多次调用 `PowerKnowledgeTool`，或者根据第一次的结果修正参数再次调用，从而实现类似人类的“追溯式研究”。

### 4.3 架构的统一性 (Uniformity)
AgentHub 的愿景是成为多 Agent 平台。除 RAG 外，未来还会有“电价计算器”、“合同生成器”等功能。将 RAG 也视为一种 Tool，使得系统内所有外部能力的接入方式保持一致（都是 Function Calling），极大地降低了系统复杂度。

## 5. 局限性与应对 (Consequences)

*   **延迟增加**：相比于直接查库，Function Calling 涉及 LLM 的一次额外推理（决定调用工具），会增加数百毫秒的延迟。
    *   *应对*：对于极度敏感的高频简单查询，保留 Service 接口作为“快速通道”。
*   **Prompt 依赖**：工具调用的准确率高度依赖于 `PowerKnowledgeQuery` 字段上的 `@Description` 描述质量。
    *   *应对*：在开发过程中，需反复测试并优化注解描述（Prompt Engineering for Tools）。

## 6. 示例代码片段

```java
// Tool 的定义
public class PowerKnowledgeTool implements Function<PowerKnowledgeQuery, PowerKnowledgeResult> {
    @Override
    public PowerKnowledgeResult apply(PowerKnowledgeQuery query) {
        // 1. 执行向量检索
        // 2. 执行重排序
        // 3. 封装结果
        return new PowerKnowledgeResult(content, references);
    }
}
```
