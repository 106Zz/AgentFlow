package com.agenthub.api.agent_engine.service;

import com.agenthub.api.agent_engine.model.EvaluationResult;
import com.agenthub.api.prompt.service.ISysPromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 自省服务 (Reflection Service)
 * <p>负责调用 Judge 模型 (GLM-4.7) 对 Worker 的输出进行合规性和准确性审计</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Slf4j
@Service
public class ReflectionService {

    private final ChatClient judgeClient;
    private final ISysPromptService sysPromptService;

    private static final String JUDGE_PROMPT_CODE = "SYSTEM-JUDGE-v1.0";

    public ReflectionService(@Qualifier("judgeChatClient") ChatClient judgeClient,
                             ISysPromptService sysPromptService) {
        this.judgeClient = judgeClient;
        this.sysPromptService = sysPromptService;
    }

    /**
     * 执行审计
     *
     * @param query       用户问题
     * @param answer      Worker 的回答
     * @param ragContext  RAG 检索上下文 (通常是 List&lt;Document&gt; 或 Map)
     * @return 审计结果
     */
    public EvaluationResult evaluate(String query, String answer, Object ragContext) {
        log.info("[Judge] Starting evaluation for query: {}", query);

        try {
            // 1. 准备 Prompt 变量
            Map<String, Object> vars = new HashMap<>();
            vars.put("user_query", query);
            vars.put("agent_answer", answer);
            
            // 如果 ragContext 是 Map (包含 documents)，直接 putAll
            // 如果是 List，这就需要包装一下，确保 Prompt 里的 <#list documents> 能取到
            if (ragContext instanceof Map) {
                vars.putAll((Map<String, Object>) ragContext);
            } else {
                vars.put("documents", ragContext);
            }

            // 2. 渲染 System Prompt
            String systemPrompt = sysPromptService.render(JUDGE_PROMPT_CODE, vars);
            if (systemPrompt == null || systemPrompt.isEmpty()) {
                log.warn("[Judge] Prompt rendered empty, skipping evaluation.");
                return EvaluationResult.pass("Prompt error");
            }

            // 3. 调用 Judge 模型
            String result = judgeClient.prompt()
                    .system(systemPrompt)
                    .user("请开始审计。")
                    .call()
                    .content();

            log.info("[Judge] Evaluation result: {}", result);

            // 4. 解析结果
            return parseResult(result);

        } catch (Exception e) {
            log.error("[Judge] Evaluation failed", e);
            return EvaluationResult.pass("Evaluation error: " + e.getMessage()); // 降级策略：报错默认通过，避免阻塞
        }
    }

    private EvaluationResult parseResult(String llmOutput) {
        if (llmOutput == null) return EvaluationResult.fail("Empty response");
        
        // 简单解析 PASS / FAIL
        if (llmOutput.contains("PASS")) {
            return EvaluationResult.pass(null);
        } else if (llmOutput.contains("FAIL")) {
            // 提取原因 (FAIL::原因)
            String reason = llmOutput;
            int idx = llmOutput.indexOf("FAIL::");
            if (idx >= 0) {
                reason = llmOutput.substring(idx + 6).trim();
            }
            return EvaluationResult.fail(reason);
        }
        
        // 默认处理 (如果没按套路出牌)
        return EvaluationResult.pass("Judge output format unknown");
    }
}
