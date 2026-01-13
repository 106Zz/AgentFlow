# AgentHub 项目全景指南 & 开发手册 (v3.0)

**版本**: 3.0  
**更新日期**: 2026-01-13  
**核心定位**: 垂直领域（能源电力）智能招投标与合规分析 Agent 平台

---

## 1. 项目概览 (Project Overview)

### 1.1 背景与愿景

AgentHub 是一个基于 Java (Spring AI) 生态构建的企业级 AI 应用。

**核心战略**: 拒绝泛化，深耕 **"能源电力行业"**。利用 41 份高质量行业文档（广东电力市场交易规则、技术规范等），打造辅助售前工程师、项目经理进行 **智能招投标** 和 **合规审查** 的专家级 Agent。

### 1.2 核心资产

| 类别 | 说明 |
|------|------|
| **数据壁垒** | 41 份电力行业核心 PDF（2025-2026 广东电力市场机制、结算规则、新能源政策） |
| **后端** | Java 21, Spring Boot 3.2, Spring Cloud Alibaba (Nacos) |
| **AI 框架** | Spring AI 1.1.0-M4, Alibaba DashScope (Qwen) |
| **存储** | PostgreSQL (PGVector + 业务数据), Redis (缓存 + 会话记忆) |
| **文件存储** | Aliyun OSS (私有 Bucket + 临时签名 URL) |
| **工具链** | Tika/PDFBox (文档解析), Knife4j (接口文档) |

---

## 2. 技术架构 (Technical Architecture)

### 2.1 RAG 引擎

- **混合解析**: 优先提取 PDF 原生文本；扫描件自动降级调用 `Qwen-VL-Plus` 多模态识别
- **智能分块**: 自研 `ChineseTextSplitter`，基于中文语义切分
- **证据溯源**: 实现 "Click-to-Download"，AI 引用红头文件时自动生成 1 小时有效期的 OSS 签名链接

### 2.2 Agent 决策体系

已完成从 "Service Pipeline" 到 **"Tool-based Agent"** 的架构演进：

```
┌─────────────────────────────────────────────────────────┐
│                    RagChatService (Router)              │
│                    意图识别 + Tool 调度                  │
└─────────────────┬───────────────────┬───────────────────┘
                  │                   │
        ┌─────────▼─────────┐ ┌───────▼────────┐
        │ PowerKnowledgeTool│ │ ComplianceTool │
        │   (政策查询)       │ │  (合规审查)     │
        └─────────┬─────────┘ └───────┬────────┘
                  │                   │
                  └───────┬───────────┘
                          ▼
              ┌───────────────────────┐
              │   VectorStore (RAG)   │
              │   41份电力行业文档      │
              └───────────────────────┘
```

**Compliance Agent 特点**:
- 内嵌独立 `ChatClient`，强制执行 RAG 检索后再比对
- 使用 `BeanOutputConverter` 输出结构化 JSON（风险评分、违规项列表）

---

## 3. 核心功能：合规审查 (Compliance Check)

### 3.1 业务流程

```
用户输入 → 自动查据 → 智能判案 → 结构化输出
   │           │           │           │
   ▼           ▼           ▼           ▼
"偏差考核   检索《2026年   规则: ±3%    风险评分 +
 免责5%"   交易细则》    现状: 5%     违规点列表 +
                        判定: 违规    原文下载链接
```

### 3.2 演进路线

| Phase | 状态 | 内容 |
|-------|------|------|
| Phase 1 | ✅ 已完成 | RAG 核心链路、文档解析 |
| Phase 2 | ✅ 已完成 | Tool 化改造 (`@Tool`)、RAG 参数动态化 |
| Phase 3 | ✅ 当前 | Nested Agent、OSS 证据链、结构化输出 |
| Phase 4 | 🔜 规划中 | Router 优化、Writer Skill、可解释输出 |

---

## 4. 后续演进方向

### 4.1 短期优先级（Demo 阶段）

1. **Router 优化**: 当前依赖模型自主选择 Tool，可加入关键词规则兜底
2. **Writer Skill**: 基于 Knowledge + Compliance 结果生成标书初稿
3. **可解释输出**: 展示"为什么合规/不合规"的推理链路

### 4.2 中期规划

| 方向 | 优先级 | 说明 |
|------|--------|------|
| Multi-Agent 编排 | 高 | Router → Worker 分层，让现有 Tool 协作更智能 |
| WebSearchTool | 中 | 实时查询电价、政策更新（非核心，锦上添花） |
| DatabaseTool | 低 | 除非有结构化电价表需求，否则暂缓 |

### 4.3 架构原则

> **能力组合，而非数量堆叠**

当前阶段的核心不是再造更多 Tool，而是：
- 让现有 Tool 被正确选用
- 合理组合产出可信结果
- 提供可解释的决策路径

---

## 5. 开发规范

### 5.1 目录结构

```
com.agenthub.api.ai
├── tool/
│   ├── knowledge/     # PowerKnowledgeTool
│   └── compliance/    # ComplianceTool + Request/Result
├── service/
│   └── ComplianceService  # 核心比对逻辑（内嵌 ChatClient）
└── config/
    └── ChatClientConfig   # AI 客户端配置
```

### 5.2 数据契约

```java
// Tool 返回结果已升级为对象列表
public record SourceDocument(
    String filename,     // 文件名
    String downloadUrl   // 临时下载链接（1小时有效）
) {}
```

### 5.3 相关文档

- `docs/adr/ADR-002`: RAG as a Tool
- `docs/tech-notes/TN-004-compliance-agent-v2.md`: 合规 Agent 架构

---

## 6. 总结

AgentHub v3.0 从"简单问答"迈向"可信交付"：

- **Nested Agent** 保证逻辑严密性
- **OSS Evidence Link** 保证数据可追溯性
- **结构化输出** 保证结果可解析性

下一步重点：**让现有能力协作得更好**，而非盲目扩展 Tool 数量。
