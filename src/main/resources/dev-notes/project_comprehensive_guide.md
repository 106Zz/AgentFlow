# AgentHub 项目全景指南 & 开发手册

**版本**: 1.0
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

### 2.2 对话与记忆 (Chat & Memory)
*   **流式响应 (Streaming)**: 全链路支持 SSE (Server-Sent Events) 流式输出。
*   **深度思考 (Reasoning)**: 特别适配 DeepSeek 等推理模型，后端通过 `StreamChunk` 结构分离 `reasoning_content` (思考过程) 和 `content` (最终回答)，前端可选择性展示。
*   **双层记忆**:
    *   **短期**: Redis 滑动窗口，保持多轮对话上下文。
    *   **长期**: PostgreSQL 持久化存储，用于审计和回溯。

### 2.3 Agent 与 Tools (进行中)
*   **架构**: 正从“单体 RAG”向“多 Agent 编排”演进。
*   **当前能力**: 具备基础 Function Calling 能力（已实现 `DateTimeTool`）。
*   **目标**: 构建 Router（路由）+ Experts（专家群）架构。

### 2.4 异步与性能
*   **线程优化**: 自定义 `WebMvcConfigurer`，将 Spring MVC 异步处理（SseEmitter/Flux）托管给独立的线程池，避免默认 `SimpleAsyncTaskExecutor` 导致的 OOM 风险。
*   **任务隔离**: 文件处理、向量化、Web 请求使用不同的线程池，互不干扰。

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
2.  **Phase 2: 能力扩展 (Tools)**
    *   **WebSearchTool**: 联网搜索最新电价政策（弥补本地文档滞后性）。
    *   **CalculatorTool**: 电力参数计算（容量、负荷）。
    *   **DocumentGenerationTool**: 将生成的文本回填到 Word 模板，生成可用的标书文件。
3.  **Phase 3: 编排与协作 (Multi-Agent)**
    *   **Router Agent**: 识别用户意图（是“写标书”还是“查政策”）。
    *   **Compliance Agent**: 专门负责拿着红头文件挑刺的“审查员”。
    *   **Writer Agent**: 专门负责润色文案的“笔杆子”。

---

## 4. 开发规范与注意事项

### 4.1 目录结构
*   `com.agenthub.api.ai`: AI 核心逻辑（RAG, Chat, Tools）。
*   `com.agenthub.api.knowledge`: 知识库管理（文档上传、切片）。
*   `com.agenthub.api.framework`: 基础设施（配置、安全、工具类）。

### 4.2 关键配置
*   `application.yml`: 包含 LLM Key、向量库配置、线程池参数。
*   **环境隔离**: 本地开发使用 `dev` 或 `local` profile，生产环境严禁直接 commit 密钥。

### 4.3 最佳实践
*   **Prompt 管理**: 尽量将 System Prompt 模板化（参考 `rag-system-prompt.st`），避免硬编码在 Java 代码中。
*   **异常处理**: RAG 链路长，必须做好降级（如 OCR 失败降级为纯文本，Rerank 失败降级为纯向量检索）。

---

## 5. 后续开发建议 (Next Steps)

1.  **丰富 Tool 库**: 优先实现 `WebSearchTool` 和 `DatabaseQueryTool`。
2.  **Agent 编排框架**: 引入 Spring AI 的高级编排特性或简单的状态机，实现 Agent 间的任务分发。
3.  **数据扩充**: 写爬虫抓取更多公开的电力行业标准（从 41 份扩展到 400 份），进一步加深行业壁垒。
4.  **前端适配**: 升级前端 UI，支持“思考过程”折叠展开，以及 PDF 原文的溯源高亮显示。

---

**总结**: AgentHub 不仅仅是一个代码仓库，它是你进入 **Vertical AI (垂直人工智能)** 领域的入场券。请保持对“电力行业”这一场景的专注，这将是你最大的差异化优势。
