package com.agenthub.api.ai.service;

import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.prompt.context.PromptContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智能路由服务 (Router Service)
 * @deprecated 已被 Agent V2 引擎取代 (CapabilityResolver + ChatAgent)。
 * 请参考 com.agenthub.api.agent_engine 包下的新实现。
 */
@Deprecated
@Slf4j
@Service
public class RouterService {

    private final ChatClient chatClient;
    private final MemorySyncService memorySyncService;

    // Spring 会自动注入所有实现了 AIUseCase 接口的 Bean (AuditUseCase, CalcUseCase, ChatUseCase)
    private final List<AIUseCase> useCases;

    public RouterService(@org.springframework.beans.factory.annotation.Qualifier("workerChatClient") ChatClient chatClient,
                         MemorySyncService memorySyncService,
                         List<AIUseCase> useCases) {
        this.chatClient = chatClient;
        this.memorySyncService = memorySyncService;
        this.useCases = useCases;
    }

    public AIResponse handleRequest(AIRequest request) {
        // 1. 识别意图 (Intent Classification)
        // 从数据库获取 Router 提示词，如果未配置则使用默认提示词
        String routerPrompt = PromptContextHolder.getRouter("ROUTER-INTENT-v1.0");
        if (routerPrompt == null || routerPrompt.isEmpty()) {
            // 降级：使用硬编码默认提示词
            routerPrompt = """
                    你是一个电力业务智能分发员。请根据用户输入和上下文判断意图，仅返回以下关键词之一：

                    1. AUDIT (合规审查):
                       - 用户明确要求对"合同"、"标书"、"文档"进行"审查"、"检查"、"核对"。
                       - **必须有文档内容才选择此意图**，如果"是否有附带文档"为"否"，绝不能选 AUDIT。

                    2. CALC (偏差计算):
                       - 用户提供了具体的电量数据（如"计划100，实际90"）。
                       - 用户询问"考核费用"、"偏差电量"、"分摊金额"等计算问题。

                    3. CHAT (知识问答/闲聊):
                       - 用户询问电力市场规则、政策解读、合规性问题（如"能不能私下签协议"、"退费返利是否合规"）。
                       - 用户咨询某些做法是否符合规定，但没有具体文档要审查。
                       - 用户进行日常闲聊。
                       - 如果无法确定是 AUDIT 或 CALC，默认返回 CHAT。

                    **关键判断规则**：
                    - 如果没有文档内容，即使提到"合同"、"标书"，也应判断为 CHAT（知识问答）
                    - 只有当用户确实有文档需要审查时，才判断为 AUDIT

                    请直接返回大写关键词，不要包含任何标点符号。
                    """;
            log.debug("[Router] 使用默认硬编码提示词（数据库未配置 ROUTER-INTENT）");
        }

        // 只有当有文档上传时，才可能是 AUDIT；否则只可能是 CALC 或 CHAT
        String intentRaw = chatClient.prompt()
                .system(routerPrompt)
                .user(u -> u.text("用户输入: {query}\n是否有附带文档: {hasDoc}")
                        .param("query", request.query())
                        .param("hasDoc", (request.docContent() != null && !request.docContent().isEmpty()) ? "是" : "否")
                )
                .call()
                .content();

        String intent = (intentRaw != null) ? intentRaw.trim().toUpperCase().replaceAll("[^A-Z]", "") : "CHAT";
        log.info(">>>> [Router] 意图识别结果: {} (原始: {})", intent, intentRaw);

        // 2. 策略分发 (Strategy Dispatch)
        AIResponse response = useCases.stream()
                .filter(u -> u.support(intent))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No UseCase found for intent: " + intent))
                .execute(request);

        // 3. 记忆同步切面 (Aspect) - 将业务结果同步给 Chat 记忆
        // 排除 STREAM (ChatUseCase 自己会存)
        if (response.getType() != AIResponse.Type.STREAM && response.getAsyncData() != null) {
            response.getAsyncData().whenComplete((result, ex) -> {
                if (ex == null && result != null) {
                    // 异步调用记忆同步服务
                    memorySyncService.sync(request.sessionId(), request.query(), result);
                }
            });
        }

        return response;
    }
}
