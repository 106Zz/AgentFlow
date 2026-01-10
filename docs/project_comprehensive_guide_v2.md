# AgentHub 项目全景指南 & 开发手册 (v2.0)

**版本**: 2.0
**更新日期**: 2026-01-10
**核心定位**: 垂直领域（能源电力）智能招投标与合规分析 Agent 平台

---

## 1. 项目概览 (Project Overview)

### 1.1 背景与愿景
AgentHub 是一个基于 Java (Spring AI) 生态构建的企业级 AI 应用。
**核心战略**: 拒绝泛化，深耕 **"能源电力行业"**。利用现有的 41 份高质量行业文档（广东电力市场交易规则、技术规范等），打造能够辅助售前工程师、项目经理进行 **智能招投标 (Bidding)** 和 **合规审查 (Compliance Check)** 的专家级 Agent。

### 1.2 核心资产
*   **数据壁垒**: 项目内置 41 份电力行业核心 PDF 文档（涵盖 2025-2026 年广东电力市场关键机制、结算规则、新能源政策等）。这是区别于通用 AI 的核心竞争力。
*   **技术栈**:
    *   **后端**: Java 21, Spring Boot 3.2, Spring Cloud Alibaba (Nacos).
    *   **AI 框架**: Spring AI (1.1.0-M4), Alibaba DashScope (Qwen/通义千问).
    *   **存储**: PostgreSQL (PGVector 向量库 + 业务数据), Redis (缓存 + 会话记忆), Aliyun OSS (文件存储).
    *   **工具链**: Tika/PDFBox (文档解析), Knife4j (接口文档).

---

## 2. 技术架构 (Technical Architecture)

### 2.1 RAG (检索增强生成) 引擎
目前已实现生产级 RAG 链路，特点如下：
*   **混合解析 (Hybrid Parsing)**:
    *   **PDF**: 采用“页级混合模式”。优先提取原生文本；若文本过少（<30字符，判定为扫描件），自动降级调用 `Qwen-VL-Plus` 进行多模态 OCR 识别。
    *   **其他文档**: 使用 Apache Tika 解析 Word/Excel/PPT。
*   **智能分块**: 摒弃通用 Splitter，自研 `ChineseTextSplitter`，基于中文段落和标点符号进行语义切分，保证知识完整性。
*   **双重检索**: `Vector Search` (向量召回) + `Rerank` (重排序)，平衡速度与精度。
*   **权限隔离**: 在向量元数据 (Metadata) 层面实现了基于 `userId` 和 `is_public` 的严格数据隔离。

### 2.2 Agent 决策体系 (Agentic Decision Making) **[NEW]**
已完成从 "Service Pipeline" 到 "Autonomous Agent" 的架构重构：
*   **Tool 化**: 将 RAG 检索能力封装为标准 AI 工具 `PowerKnowledgeTool`（详见 ADR-002）。
*   **自主决策**: 大模型不再被动接收上下文，而是根据 System Prompt 自主决定**何时查库**、**查什么年份**以及**查多少条 (TopK)**。
*   **架构分层**:
    *   **Infrastructure**: `VectorStoreHelper` (底层 I/O)。
    *   **Domain Service**: `PowerKnowledgeService` (业务逻辑)。
    *   **Tool Layer**: `PowerKnowledgeTool` (AI 接口适配)。

### 2.3 对话与记忆 (Chat & Memory)
*   **流式响应 (Streaming)**: 全链路支持 SSE (Server-Sent Events) 流式输出。
*   **深度思考 (Reasoning)**: 特别适配 DeepSeek 等推理模型，后端通过 `StreamChunk` 结构分离 `reasoning_content` (思考过程) 和 `content` (最终回答)。
*   **双层记忆**:
    *   **短期**: Redis 滑动窗口，保持多轮对话上下文。
    *   **长期**: PostgreSQL 持久化存储，用于审计和回溯。

---

## 3. 垂直领域深耕计划：能源电力招投标 Agent

**为什么做这个？**
金融领域数据贵且卷，通用领域打不过大厂。电力行业文档专业性强、壁垒高，是最佳切入点。

### 3.1 产品形态
一个虚拟的 **"标书编写团队"**：
1.  **用户**: 上传甲方发来的《招标文件》。
2.  **Agent**:
    *   自动解析需求（电压等级、工期、资质要求）。
    *   检索内部知识库（那 41 份规范）。
    *   **输出**: 生成符合国标的技术方案初稿，或指出标书中不合规的条款。

### 3.2 演进路线
1.  **Phase 1: 基础建设 (已完成)**
    *   RAG 核心链路通畅。
    *   文档解析能力具备。
2.  **Phase 2: Agent 化升级 (已完成)**
    *   实现 `PowerKnowledgeTool`。
    *   完成 Tool 注册机制的 M4 版本适配（使用 `@Tool` 注解）。
    *   大模型可根据自然语言指令动态调整检索参数（如 `topK`, `yearFilter`）。
3.  **Phase 3: 编排与协作 (Multi-Agent) (进行中)**
    *   **Router Agent**: 识别用户意图（是“写标书”还是“查政策”）。
    *   **Compliance Agent**: 专门负责拿着红头文件挑刺的“审查员”。
    *   **Writer Agent**: 专门负责润色文案的“笔杆子”。

---

## 4. 开发规范与最佳实践

### 4.1 目录结构
*   `com.agenthub.api.ai.tool`: 存放所有的 Agent 工具（如 `knowledge/PowerKnowledgeTool`）。
*   `com.agenthub.api.ai.service`: 存放领域服务（Tool 的实际执行者）。
*   `com.agenthub.api.knowledge`: 知识库管理（文档上传、切片）。
*   `docs/adr`: 架构决策记录 (Architectural Decision Records)。
*   `docs/tech-notes`: 技术实现细节笔记。

### 4.2 编码规范 (Coding Standards) **[NEW]**
*   **DTO/VO**: 必须使用 Java **Record**（详见 ADR-001）。
*   **Tool Input**: 所有的 Tool Query Record 必须包含 `@JsonCreator(mode = DELEGATING)` 工厂方法，以防止 LLM 输出单一字符串导致的反序列化错误（详见 TN-003）。
*   **Tool Definition**: 使用 `@Component` + `@Tool` 注解，废弃旧的 `Function` Bean 写法。

### 4.3 关键配置
*   `application.yml`: 包含 LLM Key、向量库配置、线程池参数。
*   **环境隔离**: 本地开发使用 `dev` 或 `local` profile，生产环境严禁直接 commit 密钥。

---

## 5. 文档体系 (Documentation)

项目已建立完整的文档资产库：
*   **ADR-001**: 使用 Java Record 作为数据载体。
*   **ADR-002**: RAG as a Tool (检索工具化)。
*   **ADR-003**: RAG 逻辑的分层与解耦。
*   **TN-001**: RAG 数据血缘与 Metadata 追踪。
*   **TN-003**: Spring AI Tool 注册与反序列化避坑指南。

---

## 6. 后续开发建议 (Next Steps)

1.  **丰富 Tool 库**: 优先实现 `WebSearchTool`（联网查电价）和 `CalculatorTool`（电费计算）。
2.  **Prompt 调优**: 进一步优化 System Prompt，教会 Agent 在何种情况下**不应该**调用工具（减少 Token 消耗）。
3.  **前端适配**: 升级前端 UI，支持展示 Tool 调用的过程（如“正在检索知识库...” -> “检索到 5 条结果”），增强用户信任感。

---

**总结**: AgentHub 正从一个“文档问答系统”进化为一个真正的“智能体平台”。每一次架构重构（如今天的 Tool 化改造）都是为了让系统更灵活、更智能。请继续保持对架构整洁性的追求。
