# 开发指南：统一会话记忆管理 (Unified Session Memory) - 领域驱动版

**目标**：打破 UseCase 之间的记忆隔离。让 "Chat" (问答) 能够深度感知到 "Calc" (计算) 和 "Audit" (审查) 的**具体业务结论**。

**核心思想**：
不是简单地存储“任务完成”，而是深入解析 `ComplianceReport` 和 `WorkerResult` 等领域对象，提取出**风险点、计算公式、审查结论**等高价值信息，将其转化为 AI 可理解的自然语言上下文。

---

## 1. 架构设计

### 1.1 痛点分析
*   **MVP 版问题**: 只存了 `Object`，摘要过于泛泛（"发现3个问题"）。Chat AI 无法回答 "哪3个问题？"
*   **Pro 版改进**: 引入领域对象解析器。
    *   遇到 `ComplianceReport`: 遍历风险项，提取 `riskDetails`。
    *   遇到 `WorkerResult`: 提取 `formula` 或 `suggestion`。

### 1.2 数据流向
```mermaid
graph TD
    Router --> |AIResponse| Result[CompletableFuture<?]]
    
    Result -.-> |完成回调| SyncService[MemorySyncService]
    
    subgraph "MemorySyncService (业务感知层)"
        SyncService --类型判断--> Parser{解析器}
        Parser --ComplianceReport--> DetailSummary["审查不通过。风险1：保证金(金额不足)..."]
        Parser --List<WorkerResult>--> CalcSummary["计算结果：500元。公式：(100-90)*5..."]
    end
    
    DetailSummary --> |@Async| Repo[MybatisChatMemoryRepository]
```

---

## 2. 代码实现步骤

### Step 1: 创建领域感知同步服务 (`MemorySyncService`)

**位置**: `src/main/java/com/agenthub/api/ai/service/MemorySyncService.java`

这里不再是简单的 `toString()`，而是针对项目核心业务实体 (`ComplianceReport`, `WorkerResult`) 的深度解析。

```java
package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.workflow.ComplianceReport;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySyncService {

    private final ChatMemoryRepository chatMemoryRepository;

    /**
     * 异步同步结果到记忆 (核心入口)
     * 使用 @Async("taskExecutor") 确保不阻塞主请求
     */
    @Async("taskExecutor")
    public void sync(String sessionId, String userQuery, Object result) {
        try {
            // 1. 领域驱动摘要 (Domain-Driven Summarization)
            String summary = generateDomainSummary(result);
            if (summary == null || summary.isEmpty()) return;

            log.info(">>>> [Memory] 同步业务数据: sessionId={}, summaryLen={}", sessionId, summary.length());

            // 2. 构造上下文 (模拟对话历史)
            List<Message> memoryBatch = List.of(
                new UserMessage(userQuery),
                new AssistantMessage(summary)
            );

            // 3. 持久化 (追加模式)
            List<Message> history = chatMemoryRepository.findByConversationId(sessionId);
            history.addAll(memoryBatch);
            
            // 滑动窗口控制 (防止上下文爆撑)
            if (history.size() > 20) {
                history = history.subList(history.size() - 20, history.size());
            }
            
            chatMemoryRepository.saveAll(sessionId, history);
            
        } catch (Exception e) {
            log.error("记忆同步异常: sessionId={}", sessionId, e);
        }
    }

    /**
     * 核心：根据业务类型生成深度摘要
     * 这样 Chat AI 才能回答 "风险详情是什么" 这种问题
     */
    private String generateDomainSummary(Object result) {
        // 场景 A: 合规审查报告 (AuditUseCase 返回)
        if (result instanceof ComplianceReport report) {
            return formatComplianceReport(report);
        }
        
        // 场景 B: 工人执行结果列表 (CalcUseCase 可能返回 List<WorkerResult>)
        if (result instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof WorkerResult) {
            @SuppressWarnings("unchecked")
            List<WorkerResult> results = (List<WorkerResult>) list;
            return formatWorkerResults(results);
        }
        
        // 场景 C: 普通文本
        if (result instanceof String str) {
            return str;
        }

        return null; // 未知类型不记录
    }

    /**
     * 格式化审查报告
     */
    private String formatComplianceReport(ComplianceReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统业务通知】我已完成合规审查。");
        sb.append("总体结论：").append(report.overallPassed() ? "通过" : "存在风险").append("。");
        sb.append("共发现 ").append(report.riskCount()).append(" 个风险项。\n");

        // 提取风险详情 (只取前 5 个，防止 Token 溢出)
        if (!report.overallPassed() && report.details() != null) {
            String risks = report.details().stream()
                    .filter(r -> !r.isPassed()) // 只看未通过的
                    .limit(5)
                    .map(r -> String.format("- [%s]: %s (建议: %s)", r.item(), r.riskDetails(), r.suggestion()))
                    .collect(Collectors.joining("\n"));
            sb.append("风险详情如下：\n").append(risks);
        }
        return sb.toString();
    }

    /**
     * 格式化计算/原子结果
     */
    private String formatWorkerResults(List<WorkerResult> results) {
        return "【系统业务通知】计算/检查结果如下：\n" + results.stream()
                .map(r -> String.format("- %s: %s (结论: %s)", r.item(), r.riskDetails(), r.isPassed() ? "OK" : "Issue"))
                .collect(Collectors.joining("\n"));
    }
}
```

### Step 2: 接入 RouterService

**位置**: `src/main/java/com/agenthub/api/ai/service/RouterService.java`

这里需要处理泛型擦除的问题。虽然 `AIResponse` 里是 `?`，但运行时的对象是具体的。

```java
// ... 注入 ...
private final MemorySyncService memorySyncService;

public AIResponse handleRequest(AIRequest request) {
    // ... 执行 ...
    AIResponse response = selectedUseCase.execute(request);
    
    // ... 拦截 ...
    if (response.getType() != AIResponse.Type.STREAM && response.getAsyncData() != null) {
        
        response.getAsyncData().whenComplete((result, ex) -> {
            if (ex == null && result != null) {
                // 这里的 result 就是运行时的 ComplianceReport 或 List<WorkerResult>
                // 交给 MemorySyncService 去由于 instanceof 动态分发
                memorySyncService.sync(request.sessionId(), request.query(), result);
            }
        });
    }
    return response;
}
```

---

## 3. 改进点总结

1.  **业务对齐**: 代码中明确引用了 `ComplianceReport` 和 `WorkerResult`，这不再是通用的 MVP，而是为您项目定制的。
2.  **内容深度**: 摘要中包含了 `riskDetails` (风险详情) 和 `suggestion` (建议)，这意味着 Chat AI 之后可以直接回答“刚才那个风险怎么改？”。
3.  **Token 控制**: 增加了 `.limit(5)` 限制，防止一份超长报告把 Chat 的上下文窗口撑爆。

---

## 4. 下一步

如果您满意这个**领域驱动**的设计，我将开始为您编写代码。

