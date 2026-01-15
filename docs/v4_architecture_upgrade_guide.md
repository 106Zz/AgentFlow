这是一个为您精心整理的、包含所有修正与核心决策的 **AgentHub v4.0 架构升级全景指南**。

该文档整合了我们关于 **Router (路由)**、**Skills (技能)**、**Workers (工人)** 和 **Workflow (工作流)** 的所有讨论，修正了之前的定义偏差，并给出了明确的代码落地路径。

---

# AgentHub v4.0 架构升级指南：从 RAG 到 Agentic Workflow

* **版本** : v4.0-MVP (收敛落地版)
* **日期** : 2026-01-14
* **目标** : 构建“显式路由 + 并行编排”架构，实现合规审查的并行化与标书编写的流程化。
* **核心理念** : **Java Workflow 负责骨架（确定性），AI Worker 负责血肉（模糊性）。**

---

## 1. 核心概念重构 (The Mental Model)

在 v4.0 中，我们严格区分以下四个层级。请务必清晰区分 **Tool** 和 **Skill** 的边界。

| 层级 | 角色类比 (电力设计院) | 定义 | 技术实现 (Spring AI) | 示例 |
| --- | --- | --- | --- | --- |
| **1. Tool (工具)** | **仪器/手册** | **原子化的硬能力**。无业务逻辑，连接外部数据/API。 | `@Tool` / `Function<T,R>` | `PowerKnowledgeTool` (查库), `CalculatorTool` (计算) |
| **2. Skill (技能)** | **审图/算量能力** | **业务化的软能力**。封装了“如何使用工具”的策略 (Prompt + Args)。 | `DomainService` + `ChatClient` | `verifyTechnicalRisk` (技术审查技能), `draftSection` (写作技能) |
| **3. Worker (工人)** | **专工 (技术员)** | **角色化的执行者**。拥有特定 Skills，负责执行 Workflow 中的一步。 | `ApplicationService` (逻辑角色) | `ComplianceWorker` (合规专工), `WriterWorker` (笔杆子) |
| **4. Workflow (流)** | **施工队长** | **流程控制者**。使用代码编排 Worker 的执行顺序 (串行/并行)。 | `OrchestratorService` (Java Code) | `ComplianceWorkflow` (并行审查), `BidWritingWorkflow` (串行生成) |
| **5. Router (路由)** | **前台接待** | **流量分发者**。识别用户意图，将请求导向对应的 Workflow。 | `RouterService` (Classifier) | 识别用户是“查问答”还是“写标书” |

---

## 2. 架构演进视图 (Architecture Evolution)

### v3.0 (当前)

> 用户 -> ChatClient (上帝视角) -> 自动判断调用 Tool -> 返回

* **问题**: 任务复杂时容易幻觉；无法处理“先A后B”的复杂逻辑；串行处理慢。

### v4.0 (目标)

> 用户 -> **Router** (分类) -> **Workflow** (Java编排) -> 并行指挥 **Worker A** & **Worker B** -> 各自调用 **Skill** -> 底层 **Tool** -> 汇总返回

---

## 3. 分阶段执行计划 (Execution Plan)

### Phase 1: 基础设施层 (Infrastructure & Tools)

**目标**: 改造底层工具，使其支持被上层 Skill 精准调用。

#### Step 1.1: 改造 `PowerKnowledgeQuery` (兼容性升级)

为了让 Skill 能指定查询范围（如：只查商务标），我们需要在 Input Record 中增加 `category` 字段。

* **文件**: `com.agenthub.api.ai.tool.knowledge.PowerKnowledgeQuery.java`
* **变更**:
```java
public record PowerKnowledgeQuery(
    @JsonPropertyDescription("查询的具体问题") String query,

    @JsonPropertyDescription("[v4新增] 文档类别过滤: TECHNICAL(技术), BUSINESS(商务), LEGAL(法规)") 
    String category, 

    Integer topK
) {
    // 保持 v3 的兼容性 (Delegating Mode)，防止旧 Agent 崩溃
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public PowerKnowledgeQuery(...) { ... }
}

```


* **服务层**: 修改 `PowerKnowledgeService`，在检索 VectorStore 时，如果 `category` 不为空，则添加 Metadata Filter。

---

### Phase 2: 领域技能层 (Domain Skills) —— *核心增量*

**目标**: 将 Prompt 和 Tool 的配置封装为可复用的“技能”。

#### Step 2.1: 实现 `ComplianceSkills`

* **文件**: `com.agenthub.api.ai.service.skill.ComplianceSkills.java`
* **逻辑**:
```java
@Service
public class ComplianceSkills {
    private final ChatClient chatClient;

    // Skill A: 技术合规审查技能
    public String verifyTechnical(String content) {
        return chatClient.prompt()
            .system("你是电力技术专家。请调用工具查询《技术规范》进行比对。") 
            .user(content)
            .tools("powerKnowledgeTool") // 挂载底层工具
            .call()
            .content();
    }

    // Skill B: 商务合规审查技能
    public String verifyCommercial(String content) {
        return chatClient.prompt()
            .system("你是商务造价专家。请调用工具查询《交易规则》或《结算细则》。") 
            .user(content)
            .tools("powerKnowledgeTool") 
            .call()
            .content();
    }
}

```



---

### Phase 3: 应用编排层 (Worker & Workflow)

**目标**: 使用 Java 代码实现并行化与流程控制。

#### Step 3.1: 定义 Worker (逻辑角色)

* **文件**: `com.agenthub.api.ai.worker.ComplianceWorker.java`
* **逻辑**: Worker 负责调用 Skill 并处理异常/重试。
```java
@Service
public class ComplianceWorker {
    private final ComplianceSkills skills;

    public ComplianceResult doTechnicalCheck(String section) {
        // 调用 Skill，并封装结果
        String raw = skills.verifyTechnical(section);
        return parseResult(raw); // 转为结构化对象
    }
    // ... doCommercialCheck
}

```



#### Step 3.2: 实现 Workflow Orchestrator (并行化)

* **文件**: `com.agenthub.api.ai.service.ComplianceWorkflowService.java`
* **核心**: 使用 `CompletableFuture` 实现 **并行分治 (Parallelization)**。
```java
public ComplianceReport execute(String tenderDoc) {
    // 1. 切分文档 (Pre-processing)
    var sections = splitDoc(tenderDoc); 

    // 2. 并行启动 Worker (Parallelization)
    // 以前是串行，现在两个线程同时跑，速度提升 50%
    var techTask = CompletableFuture.supplyAsync(() -> worker.doTechnicalCheck(sections.get("tech")));
    var bizTask = CompletableFuture.supplyAsync(() -> worker.doCommercialCheck(sections.get("biz")));

    // 3. 等待汇合 (Fan-in)
    CompletableFuture.allOf(techTask, bizTask).join();

    // 4. 生成报告
    return new ComplianceReport(techTask.get(), bizTask.get());
}

```



---

### Phase 4: 路由层 (The Router)

**目标**: 建立统一入口，分发流量。

#### Step 4.1: 定义意图与路由服务

* **文件**: `com.agenthub.api.ai.router.RouterService.java`
* **逻辑**:
```java
public UserIntent classify(String input) {
    // System Prompt: "如果包含'审查'去 COMPLIANCE，如果包含'写方案'去 WRITING..."
    return chatClient.prompt().user(input).call().entity(UserIntent.class);
}

```


* **Controller 改造**:
```java
UserIntent intent = router.classify(query);
if (intent.type == COMPLIANCE) {
    return complianceWorkflow.execute(query); // 走并行工作流
} else {
    return ragService.chat(query); // 走普通问答
}

```



---

## 4. 关键问题解答 (FAQ & Evaluation)

### Q1: 为什么要并行化？(Why Parallelize?)

1. **降低延迟 (Latency)**: 合规审查涉及多次 RAG 检索和推理。串行执行耗时太长（如 90秒），并行可以将时间压缩至最长单任务时间（如 30秒）。
2. **专注度 (Attention)**: 将大任务拆解为小任务（技术 vs 商务），Worker 专注度更高，上下文更短，减少幻觉。

### Q2: 既然有 Tool，为什么还要 Skill？

* **解耦**: Tool 只管“物理连接”（怎么连数据库）。Skill 管“业务策略”（查什么、怎么问）。
* **复用**: 同一个 `PowerKnowledgeTool` 可以被 `TechnicalSkill`（查技术）和 `CommercialSkill`（查价格）复用，只需改变参数配置。

### Q3: 如何评估 (Evaluation) v4.0 的效果？

在 MVP 阶段，采用 **工程化校验**：

1. **结构化校验**: `BeanOutputConverter` 是否报错？JSON 字段是否完整？
2. **引用溯源校验**: 检查返回的 `source` 字段。如果 Worker 输出了观点但 `source` 为空，视为 FAIL（幻觉）。

---

## 5. 面试/汇报 必杀技 (The "Architect" Narrative)

如果有人问：**“为什么你们 v4.0 不用全自动 Agent，而是用 Java 写死流程？”**

> **标准回答**:
> “这是一个基于**能源电力行业特性**的架构决策。
> 1. **容错率**: 电力招投标对准确性要求极高。全自动 Agent (Autonomous Planning) 容易产生路径规划错误，漏掉关键审查项。
> 2. **确定性**: 我们采用 **'Workflow-first'** 策略，用 Java 代码保证核心流程（技术、商务、法务）的 **100% 覆盖率**，而将 AI 的不确定性限制在单个 Worker 的 Prompt 内部。
> 3. **可调试性**: 这种架构让我们在出现问题时，能迅速定位是‘哪个 Worker’出了错，而不是在一个黑盒的 Agent 链路中瞎猜。
>
>
> 我们是在用 **Java 保证骨架的刚性，用 AI 赋予血肉的灵性**。”

---

**下一步行动 (Next Action)**:
请立即开始 **Phase 1 (Tool 改造)**，为后续的 Skill 和 Worker 铺平道路。
