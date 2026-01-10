# TN-003: Spring AI Tool 注册机制与反序列化容错实战记录

* **日期**: 2026-01-10
* **模块**: AI Core / Tool Integration
* **涉及组件**: `Spring AI 1.1.0-M4`, `Jackson`, `PowerKnowledgeTool`
* **状态**: 已解决 (Resolved)

---

## 1. 背景 (Context)

在将 RAG 系统从简单的 "Pipeline 模式"（强制检索）升级为 "Agent 模式"（自主工具调用）的过程中，我们遭遇了连续的三个技术阻碍。

目标架构：
* **Input**: 用户自然语言问题。
* **Decision**: 大模型自主决定是否调用 `powerKnowledge` 工具，以及参数如何填写（`topK` 动态调整）。
* **Execution**: Spring AI 执行 Java 方法并返回结果。

---

## 2. 问题一：工具注册方式变更 (The Registration Trap)

### 2.1 现象
启动后调用接口，报错：
```text
java.lang.IllegalStateException: No @Tool annotated methods found in powerKnowledge.
Did you mean to pass a ToolCallback or ToolCallbackProvider? 
If so, you have to use .toolCallbacks() instead of .tool()
```

### 2.2 原因分析

在 Spring AI 1.1.0-M4 版本中，`.defaultTools("beanName")` 的行为发生了 Breaking Change：

* 它不再支持寻找 `java.util.function.Function` 类型的 Bean。
* 它默认期望找到一个包含 `@Tool` 注解方法的普通 Component Bean。

### 2.3 解决方案

放弃 `Config + @Bean` 的旧写法，采用 **Component + @Tool** 的新标准写法。

**1. 删除旧文件**: `KnowledgeToolConfig.java`
**2. 新建 Tool 组件**: `PowerKnowledgeTool.java`

```java
@Component
@RequiredArgsConstructor
public class PowerKnowledgeTool {
    private final PowerKnowledgeService service;

    @Tool(description = "【必须调用】广东电力市场专属知识库工具...")
    public PowerKnowledgeResult retrieve(PowerKnowledgeQuery query) {
        return service.retrieve(query);
    }
}
```

**3. 修改 ChatClient 构建方式**:

```java
// RagChatServiceImpl.java
private final PowerKnowledgeTool powerKnowledgeTool; // 注入实例

return ChatClient.builder(chatModel)
        .defaultTools(powerKnowledgeTool) // 传入实例对象，而非字符串名字
        .build();
```

---

## 3. 问题二：隐式依赖缺失 (The Missing Dependency)

### 3.1 现象

修复注册问题后，报错：

```text
java.lang.NoClassDefFoundError: com/github/victools/jsonschema/generator/AnnotationHelper
at org.springframework.ai.util.json.schema.JsonSchemaGenerator.generateForMethodInput
```

### 3.2 原因分析

Spring AI 在解析 `@Tool` 注解并生成 JSON Schema 发送给大模型时，底层依赖 `victools` 库。
`spring-ai-core` 可能没有传递性引入完整的 Schema 生成器依赖，导致运行时缺类。

### 3.3 解决方案

在 `pom.xml` 中显式补全依赖：

```xml
<dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-generator</artifactId>
    <version>4.31.1</version>
</dependency>
<dependency>
    <groupId>com.github.victools</groupId>
    <artifactId>jsonschema-module-jackson</artifactId>
    <version>4.31.1</version>
</dependency>
```

---

## 4. 问题三：LLM 参数反序列化失败 (The Mismatched Input)

### 4.1 现象

大模型决定调用工具，但抛出异常：

```text
com.fasterxml.jackson.databind.exc.MismatchedInputException: 
Cannot construct instance of `PowerKnowledgeQuery` ... 
no String-argument constructor/factory method to deserialize from String value 
('2026年偏差考核规则 计算方法')
```

### 4.2 原因分析

这是 LLM 的一种“偷懒”行为，也是 Java 强类型的痛点：

1. **预期**: Java 期望接收 JSON 对象 `{"query": "2026年..."}`。
2. **实际**: 大模型认为只有一个核心参数，直接传回了字符串 `"2026年..."`。
3. **冲突**: Jackson 默认无法将 `String` 直接转换为 `Record` 对象。

### 4.3 解决方案 (Critical Fix)

在 `PowerKnowledgeQuery` Record 中增加**委托模式（Delegating Mode）**的静态工厂方法。

**代码变更 (`PowerKnowledgeQuery.java`)**:

```java
public record PowerKnowledgeQuery(
    @JsonProperty(required = true) String query,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer topK,
    @JsonInclude(JsonInclude.Include.NON_NULL) String yearFilter
) {
    /**
     * 核心修复：
     * mode = DELEGATING 告诉 Jackson：
     * 如果遇到单一字符串输入，直接调用此方法，不要尝试匹配字段。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PowerKnowledgeQuery fromString(String query) {
        return new PowerKnowledgeQuery(query, null, null);
    }
}
```

---

## 5. 最终成果与观察 (The Outcome)

问题解决后，系统日志显示 Agent 开始展现出**高级智能行为**。

### 5.1 动态参数调整

日志记录显示：

```text
INFO: 🔍 AI 正在调用知识库工具: query=偏差考核 计算方法 结算规则 2026, year=2026, topK=5
INFO: 🔍 AI 正在调用知识库工具: query=广东电网客服电话, year=null, topK=3
```

**分析**:

* `topK` 不是写死的。
* AI 阅读了 `@JsonPropertyDescription` 中的提示（"如果问题需要广泛信息，设为5"）。
* **Agent 自主判断**：对于“偏差考核”这种复杂计算问题，它主动将 `topK` 提升为 5；对于简单问题，可能保持默认。

### 5.2 结论

此次重构成功将系统从 **Rule-Based RAG** 升级为 **Decision-Based Agent**。

1. **代码更规范**：`@Tool` 写法符合 Spring AI 未来标准。
2. **系统更健壮**：`@JsonCreator` 修复了 LLM 输出不稳定的 Crash 风险。
3. **智能度提升**：模型能够根据问题复杂度动态调整检索策略。

---

## 6. 最佳实践总结 (Best Practices)

1. **Tool 定义**: 始终使用 `@Component` + `@Tool`，避免使用 `Function Bean`。
2. **Record 设计**: 所有的 Tool Input Record 都**必须**包含 `@JsonCreator(mode = DELEGATING)` 方法，以防御大模型的非标准输出。
3. **Prompt 引导**: 在字段描述（`@JsonPropertyDescription`）中给出的建议（如 `topK` 的设置），大模型是真的会听并执行的。
