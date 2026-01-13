# TN-004: 合规审查智能体 (Compliance Agent) 架构演进与实现

* **日期** : 2026-01-13
* **状态** : 已完成 (Implemented)
* **模块** : AI Core / Compliance
* **标签** : `Nested Agent`, `Structured Output`, `Vertical RAG`

## 1. 问题背景 (The Problem & Questions)

在 AgentHub 从单纯的问答系统向垂直领域 Agent 演进的过程中，我们在 **Phase 2** 遇到了以下战略和技术困惑：

1. **战略摇摆**：是做一个通用的 **WebSearchTool** (联网搜索) 去爬取外部不确定的标书，还是深挖内部 41 份核心电力文档的价值？
2. **工具边界**：合规审查 (Compliance Check) 到底是一个 Tool，还是一个独立的 Agent？它需要自己的 `ChatClient` 吗？
3. **架构实现**：
* 如何在 Tool 内部精准调用 RAG 检索？
* 如何强制大模型输出 `true/false` 和结构化数据，而不是说空话？
* `RagChatServiceImpl` 如何集成这个新能力？


## 2. 解决方案与决策 (Resolution)

经过架构评审，我们做出了以下核心决策：

### 2.1 战略决策：深耕垂直壁垒

* **否决 WebSearch**：暂时搁置联网搜索。泛化的搜索会稀释行业壁垒。
* **确立 Compliance Core**：核心价值在于利用现有的 41 份“电力红头文件”（权威规则），对用户上传的标书/合同进行“找茬”和“审计”。这才是高价值交付。

### 2.2 架构决策：分层与嵌套 (Nested Architecture)

* **俄罗斯套娃模式**：
* **外层 (Router)**：`RagChatServiceImpl` 作为大堂经理，负责识别用户意图并路由给 Tool。
* **内层 (Worker)**：`ComplianceService` 内部维护一个**独立**的 `ChatClient`，专门用于执行“比对”任务。


* **执行确定性**：在 `ComplianceCheckTool` 内部，**强制调用** `PowerKnowledgeService.retrieve()` 获取规则，不让 AI 决策“要不要查”，而是“必须查”。

### 2.3 技术决策：结构化输出

使用 Spring AI 的 `BeanOutputConverter` 将 Java Record 转换为 JSON Schema，强制 LLM 输出严格符合定义的 JSON 数据。

## 3. 最终代码实现 (Implementation)

以下是最终落地的核心代码结构。

### 3.1 定义数据契约 (Record)

遵循 ADR-001，使用 Record 定义输入输出。

```java
// File: com.agenthub.api.ai.tool.compliance.ComplianceCheckRequest.java
public record ComplianceCheckRequest(
    String content, // 待审文本
    String scene    // 场景：BIDDING/CONTRACT
) {}

// File: com.agenthub.api.ai.tool.compliance.ComplianceCheckResult.java
public record ComplianceCheckResult(
    boolean passed,
    double riskScore,
    List<ComplianceIssue> issues,
    String summary
) {}

```

### 3.2 核心业务逻辑 (The Brain)

这是“幕后法官”。它利用 `PowerKnowledgeService` 查据，利用内部 `ChatClient` 判案。

```java
// File: com.agenthub.api.ai.service.ComplianceService.java
@Service
@RequiredArgsConstructor
public class ComplianceService {

    private final PowerKnowledgeService knowledgeService; // 复用 RAG 检索
    private final ChatClient.Builder chatClientBuilder;  // 注入 Builder 用于构建内部 AI

    public ComplianceCheckResult audit(ComplianceCheckRequest request) {
        // 1. 强制执行 RAG 检索 (调用 Service 层，而非 Tool)
        PowerKnowledgeQuery ragQuery = new PowerKnowledgeQuery(request.content(), 5, null);
        PowerKnowledgeResult ragResult = knowledgeService.retrieve(ragQuery);
        
        // 2. 准备上下文
        String ruleContext = String.join("\n---\n", ragResult.rawContentSnippets());
        
        // 3. 准备转换器 (Schema 注入)
        var converter = new BeanOutputConverter<>(ComplianceCheckResult.class);
        
        // 4. 构建 Prompt (包含规则 + 待审文本 + JSON约束)
        String prompt = """
            【权威规则】：%s
            【待审文本】：%s
            请审查待审文本是否违规。
            %s 
            """.formatted(ruleContext, request.content(), converter.getFormat());

        // 5. 内部 AI 执行比对
        String jsonResult = chatClientBuilder.build()
                .prompt()
                .user(prompt)
                .call()
                .content();

        // 6. 转换回 Java 对象
        return converter.convert(jsonResult);
    }
}

```

### 3.3 工具封装 (The Interface)

对外暴露的标准工具接口。

```java
// File: com.agenthub.api.ai.tool.compliance.ComplianceCheckTool.java
@Component
public class ComplianceCheckTool {
    private final ComplianceService complianceService;

    @Tool(description = "合规性审查工具。当用户要求'检查标书'、'审核合同'时调用。")
    public ComplianceCheckResult check(ComplianceCheckRequest request) {
        return complianceService.audit(request);
    }
}

```

### 3.4 集成入口 (The Router)

在主聊天服务中挂载新工具。

```java
// File: com.agenthub.api.ai.service.impl.RagChatServiceImpl.java
public ChatClient createUserChatClient(String sessionId) {
    return ChatClient.builder(chatModel)
            .defaultSystem(systemPromptResource)
            // 同时挂载 RAG工具 和 合规工具
            .defaultTools(powerKnowledgeTool, complianceCheckTool) 
            .build();
}

```

## 4. 最终调用流程视图 (Call Flow)

现在的系统运行方式如下：

1. **User**: "帮我看看这一段标书合规吗：偏差考核免责 5%"。
2. **RagChatService (Outer AI)**:
* 思考：用户意图是 Check -> 决定调用 `complianceCheckTool`。
* 参数提取：`content = "偏差考核免责 5%"`。


3. **ComplianceCheckTool**: 接到请求，转发给 Service。
4. **ComplianceService (Logic)**:
* **Step A (查据)**: 调用 `knowledgeService.retrieve("偏差考核 5%...")`。
* **Return**: 拿到《2026实施细则》片段，文中写着“免责范围 ±3%”。
* **Step B (判案)**: 启动内部 `ChatClient`。
* **Prompt**: "规则是3%，用户写5%，合规吗？请输出JSON。"

5. **Inner AI**: 思考比对 -> 输出 `{"passed": false, "issue": "5% > 3%"}`。
6. **RagChatService (Outer AI)**:
* 收到 JSON 结果。
* **最终回复**: "审查未通过。根据《2026实施细则》，免责范围应为 ±3%，而您填写的是 5%，存在高风险。"



---

**总结 (Architect's Note)**:
这次重构标志着 AgentHub 从简单的“知识搬运工”升级为了具备“逻辑判断能力”的**垂直领域专家**。我们没有盲目追求 WebSearch 的广度，而是通过 **Nested Agent (嵌套智能体)** 架构，在电力合规这一深度场景上打穿了价值。

```