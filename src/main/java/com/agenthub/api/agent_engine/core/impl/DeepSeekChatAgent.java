package com.agenthub.api.agent_engine.core.impl;

import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.core.SinglePassExecutor;
import com.agenthub.api.agent_engine.model.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * DeepSeekChatAgent - 一个基于 DeepSeek 模型的聊天代理实现
 * <p>V3.0: 逻辑重构为单次执行流程，实现了 "意图识别 → 预检索 → LLM → Judge" 单次通过</p>
 *
 * <h3>执行流程：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  1. 意图识别 (IntentRecognition)                                    │
 * │     └─ 识别用户意图: KB_QA / CHAT / UNKNOWN                       │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  2. 预检索 (PreRetrieval) - 仅 KB_QA 且高置信度                       │
 * │     └─ 调用 knowledge_search → 获取 EvidenceBlock                  │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  3. LLM 生成 (Stream)                                               │
 * │     └─ 流式输出思考过程 + 最终回答                                   │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  4. 保存记忆 (SaveMemory)                                           │
 * │     └─ 保存 User + Assistant 到 sys_ai_memory (滑动窗口)              │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  5. Judge 审计 (Async) - 不阻塞响应                                 │
 * │     └─ 异步评估回答质量，用于事后分析                               │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
public class DeepSeekChatAgent implements ChatAgent {

    // ==================== 依赖注入组件 ====================

    private final SinglePassExecutor singlePassExecutor;

    public DeepSeekChatAgent(SinglePassExecutor singlePassExecutor) {
        this.singlePassExecutor = singlePassExecutor;
    }

    @Override
    public Flux<String> stream(AgentContext context) {
        log.info("[Agent] Delegating to SinglePassExecutor... Session: {}", context.getSessionId());
        return singlePassExecutor.executeStream(context);
    }
}
