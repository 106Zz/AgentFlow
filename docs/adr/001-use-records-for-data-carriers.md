# ADR-001: 在 AgentHub 中采用 Java Record 作为数据载体

* **状态**: 已采纳 (Accepted)
* **日期**: 2026-01-10
* **决策人**: AgentHub 架构组
* **适用范围**: Tool 输入/输出, Agent 消息体, Function Calling Schema

---

## 1. 背景 (Context)

随着 AgentHub 从「单体 RAG 应用」向「垂直领域多 Agent 编排平台」演进，系统组件间的通信复杂度显著增加。

在当前的架构（Spring AI + Java 21）中，出现了大量用于**跨组件传递**的数据对象，典型场景包括：
* **Tool I/O**: LLM 调用本地工具时的参数（如 `PowerKnowledgeQuery`）及工具返回的结构化结果。
* **Agent 消息**: 不同 Agent（如 Router 和 Compliance Expert）之间交换的上下文。
* **状态快照**: 用于持久化或审计的中间状态数据。

这些对象的共同特征是：
1.  **纯数据承载**：不包含复杂的业务行为。
2.  **高频传递**：在调用链路中被频繁序列化/反序列化。
3.  **一致性要求**：在一次推理生命周期内，不应被中途修改。

我们需要确定一种标准的 Java 语法结构来定义这些对象，以保证代码的一致性和系统的稳定性。

## 2. 备选方案 (Options Considered)

我们评估了以下三种实现方式：

* **方案 A: 普通 POJO Class**
    * 使用标准的 getter/setter。
    * *缺点*：代码冗余，默认可变（Mutable），容易引入副作用。
* **方案 B: Lombok `@Value` / `@Data`**
    * 使用注解减少样板代码。
    * *缺点*：引入第三方依赖，且在 Spring AI 的 Function Calling 反射机制中偶尔存在兼容性边缘情况。
* **方案 C: Java Record (JDK 14+)**
    * 原生支持的不可变数据载体。
    * *优点*：语义明确，线程安全，零样板代码。

## 3. 决策 (Decision)

**我们要使用 Java `record`。**

在 AgentHub 项目中，所有**不具备生命周期管理、仅用于数据传输的对象（DTO / Value Object）**，必须优先使用 `record` 定义。

## 4. 决策理由 (Rationale)

### 4.1 不可变性 (Immutability) 是 Agent 架构的刚需
在多 Agent 和 RAG 链路中，数据对象往往跨越多个线程（如 Spring MVC 线程池 -> RAG 检索线程 -> LLM 响应流）。
* `record` 的所有字段默认为 `final`，且无 setter。
* 这消除了“数据在传递过程中被意外修改”的风险，极大地降低了并发调试的难度。

### 4.2 语义契合 Function Calling
Spring AI 利用反射机制将 Java 类映射为 JSON Schema 供大模型理解。
* `record` 的构造函数和字段定义紧凑且强制。
* 相比于普通 Class 容易混入 helper method 或内部状态，`record` 强制开发者保持“纯数据”结构，这让生成的 JSON Schema 更干净，减少大模型的理解幻觉。

### 4.3 降低“无意识复杂度”
使用 `record` 是一种架构上的**结构性约束**：
* 它向开发者传递明确信号：“这里只能放数据，不能写复杂的业务逻辑”。
* 这防止了 Tool 的定义类逐渐膨胀为包含业务逻辑的“胖对象”，有助于维持 Tool 与 Service 的清晰边界。

### 4.4 原生生态支持
Java 21 和 Spring Boot 3.2 对 record 有着完美的序列化（Jackson）支持，且无需引入 Lombok 等额外编译时处理，符合项目追求原生、轻量的技术栈定位。

## 5. 示例 (Examples)

### 正确示范 (Positive)
用于 Tool 的输入定义：

```java
@JsonClassDescription("电力知识库查询参数")
public record PowerKnowledgeQuery(
    @JsonPropertyDescription("业务问题") String question,
    @JsonPropertyDescription("文档范围") List<String> docScopeIds
) {}
```

### 错误示范 (Negative)

避免使用可变的 Class 作为 Tool 输入：

```java
// ❌ 避免这样做
public class PowerKnowledgeQuery {
    private String question;
    // 这种 setter 允许状态在传递中被污染
    public void setQuestion(String question) { this.question = question; } 
    
    // 容易混入不该有的逻辑
    public void validate() { ... } 
}
```

## 6. 例外场景 (Exceptions)

以下情况**不使用** record，而应使用普通 `class`：

1. **Service / Component**：需要被 Spring 容器管理 (`@Service`, `@Component`) 的 Bean。
2. **JPA Entity**：虽然 JPA 开始支持 record，但在处理延迟加载和代理时，普通 class 目前仍是更稳健的选择。
3. **继承关系**：当必须使用继承复用代码时（record 不支持继承）。

## 7. 后果 (Consequences)

* **正面**：代码行数减少，线程安全性提高，Schema 定义更规范。
* **负面**：无法使用继承（Inheritance），若需要复用字段，需采用组合（Composition）或接口（Interface）。
