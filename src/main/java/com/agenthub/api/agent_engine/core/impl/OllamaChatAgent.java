package com.agenthub.api.agent_engine.core.impl;

import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.core.SinglePassExecutor;
import com.agenthub.api.agent_engine.model.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 本地模型聊天代理（双模型路由版）
 * <p>V4.0: 双模型路由，KB_QA 走微调模型，CHAT 走基座模型</p>
 *
 * <h3>执行流程：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  1. 意图识别 (IntentRecognition)                                    │
 * │     └─ 识别用户意图: KB_QA / CHAT / UNKNOWN                       │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  2. 预检索 (PreRetrieval) - 仅 KB_QA                                │
 * │     └─ 调用 knowledge_search → 获取 EvidenceBlock                  │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  3. LLM 生成 (Stream) - 按意图分流模型                              │
 * │     ├─ KB_QA → 微调模型 (qwen3.5-agenthub) + knowledge_search     │
 * │     └─ CHAT → 基座模型 (qwen3.5) + 其他工具                        │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  4. 保存记忆 (SaveMemory)                                           │
 * │     └─ 保存 User + Assistant 到 sys_ai_memory (滑动窗口)            │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  5. Judge 审计 (Async) - 不阻塞响应                                 │
 * │     └─ 异步评估回答质量，用于事后分析                               │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
public class OllamaChatAgent implements ChatAgent {

    // ==================== 依赖注入组件 ====================

    private final SinglePassExecutor singlePassExecutor;

    public OllamaChatAgent(SinglePassExecutor singlePassExecutor) {
        this.singlePassExecutor = singlePassExecutor;
    }

    @Override
    public Flux<String> stream(AgentContext context) {
        log.info("[Agent] Delegating to SinglePassExecutor (dual-model routing)... Session: {}", context.getSessionId());
        return singlePassExecutor.executeStream(context);
    }
}
