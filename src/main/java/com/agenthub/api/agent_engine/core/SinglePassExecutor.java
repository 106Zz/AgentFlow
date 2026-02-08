package com.agenthub.api.agent_engine.core;

import com.agenthub.api.agent_engine.capability.ToolRegistry;
import com.agenthub.api.agent_engine.config.AgentModelFactory;
import com.agenthub.api.agent_engine.config.DashScopeNativeService;
import com.agenthub.api.agent_engine.model.*;
import com.agenthub.api.agent_engine.service.IntentRecognitionService;
import com.agenthub.api.agent_engine.service.ReflectionService;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.llm.StreamCallback;
import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 单次执行器
 * <p>实现 "意图识别 → 预检索 → LLM输出 → Judge事后审计" 的单次流程</p>
 *
 * <h3>与多轮循环的区别：</h3>
 * <pre>
 * 多轮循环 (AgentLoopExecutor):
 *   QuickThink → ExecuteTool → DeepThink → Reflect → Loop (最多5轮)
 *
 * 单次执行 (SinglePassExecutor):
 *   Intent → PreRetrieval → LLM (单次) → Judge (异步审计)
 * </pre>
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
 * │  3. 构建消息 (BuildMessages)                                         │
 * │     ├─ 加载历史记录 (ChatMemoryRepository)                         │
 * │     ├─ 滑动窗口控制 (保留最近20条)                                  │
 * │     └─ 组装 System + Context + User                                │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  4. LLM 生成 (Stream)                                               │
 * │     └─ 流式输出思考过程 + 最终回答                                   │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  5. 保存记忆 (SaveMemory)                                           │
 * │     └─ 保存 User + Assistant 到 sys_ai_memory                        │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  6. Judge 审计 (Async) - 不阻塞响应                                 │
 * │     └─ 异步评估回答质量，用于事后分析                               │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @author AgentHub
 * @since 2026-02-07
 */
@Slf4j
public class SinglePassExecutor {

    // ==================== 依赖服务 ====================

    private final ChatClient workerClient;
    private final IntentRecognitionService intentRecognition;
    private final PowerKnowledgeService powerKnowledgeService;
    private final ToolRegistry toolRegistry;
    private final ReflectionService reflectionService;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ISysPromptService sysPromptService;
    private final ObjectMapper objectMapper;
    private final DashScopeNativeService nativeService;

    // ==================== 常量配置 ====================

    /**
     * 工作模型配置
     */
    private static final String WORKER_MODEL = AgentModelFactory.WORKER_MODEL;

    /**
     * 滑动窗口大小 (保留最近 N 条历史消息)
     */
    private static final int MEMORY_WINDOW_SIZE = 20;

    /**
     * 系统提示词模板代码
     */
    private static final String SYSTEM_PROMPT_CODE = "SYSTEM-RAG-v1.0";

    /**
     * JSON 块提取正则 (用于提取工具调用)
     */
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL
    );

    // ==================== 构造器 ====================

    public SinglePassExecutor(
            ChatClient workerClient,
            IntentRecognitionService intentRecognition,
            PowerKnowledgeService powerKnowledgeService,
            ToolRegistry toolRegistry,
            ReflectionService reflectionService,
            ChatMemoryRepository chatMemoryRepository,
            ISysPromptService sysPromptService,
            ObjectMapper objectMapper,
            DashScopeNativeService nativeService) {
        this.workerClient = workerClient;
        this.intentRecognition = intentRecognition;
        this.powerKnowledgeService = powerKnowledgeService;
        this.toolRegistry = toolRegistry;
        this.reflectionService = reflectionService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.sysPromptService = sysPromptService;
        this.objectMapper = objectMapper;
        this.nativeService = nativeService;
    }

    // ==================== 核心执行方法 ====================

    /**
     * 流式执行单次流程
     *
     * @param context Agent 上下文
     * @return 响应流 (包含思考过程和最终回答)
     */
    public Flux<String> executeStream(AgentContext context) {
        log.info("[SinglePass] 开始执行: sessionId={}, query={}",
                context.getSessionId(), context.getQuery());

        // 用于收集完整回答 (用于保存记忆和 Judge 审计)
        StringBuilder fullAnswer = new StringBuilder();
        StringBuilder thinkingContent = new StringBuilder();

        return Flux.create(sink -> {
            try {
                // ==================== 1. 意图识别 ====================
                IntentResult intentResult = intentRecognition.recognizeIntent(context.getQuery());
                context.setIntent(intentResult.intent());
                context.setIntentConfidence(intentResult.confidence());

                log.info("[SinglePass] 意图识别结果: intent={}, confidence={}, needsPreRetrieval={}",
                        intentResult.intent(), intentResult.confidence(), intentResult.needsPreRetrieval());

                // ==================== 2. 预检索 (仅 KB_QA 且高置信度) ====================
                String evidenceContext = "";
                if (intentResult.needsPreRetrieval()) {
                    log.info("[SinglePass] 触发预检索: query={}", context.getQuery());

                    // 发送工具调用事件
                    sink.next("__TOOL_CALL__:knowledge_search\n");

                    long retrieveStart = System.currentTimeMillis();
                    try {
                        var knowledgeResult = powerKnowledgeService.retrieve(
                                new com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery(
                                        context.getQuery(),
                                        5,  // topK
                                        null,  // year
                                        null   // category
                                )
                        );

                        evidenceContext = formatKnowledgeResult(knowledgeResult);
                        context.setPreRetrievedContent(evidenceContext);
                        context.setPreRetrievalDone(true);

                        log.info("[SinglePass] 预检索完成: 耗时={}ms, evidenceBlocks={}",
                                System.currentTimeMillis() - retrieveStart,
                                knowledgeResult.getEvidenceBlockCount());

                    } catch (Exception e) {
                        log.error("[SinglePass] 预检索失败", e);
                        evidenceContext = "【检索失败】知识库暂时无法访问，请稍后重试。";
                    }
                }

                // ==================== 3. 构建消息 ====================
                List<Message> messages = buildMessages(context, evidenceContext);
                log.debug("[SinglePass] 消息构建完成: 数量={}", messages.size());

                // ==================== 4. LLM 生成 (流式) ====================
                doStreamChat(messages, context)
                        .doOnNext(chunk -> {
                            // 处理思考过程标记
                            if (chunk.contains("@@THINK_START@@")) {
                                sink.next(chunk.replace("@@THINK_START@@", ""));
                                return;
                            }
                            if (chunk.contains("@@THINK_END@@")) {
                                sink.next(chunk.replace("@@THINK_END@@", ""));
                                return;
                            }

                            // 收集内容
                            if (!chunk.isEmpty()) {
                                fullAnswer.append(chunk);
                                sink.next(chunk);
                            }
                        })
                        .doOnComplete(() -> {
                            log.info("[SinglePass] LLM 生成完成: 回答长度={}", fullAnswer.length());

                            // ==================== 5. 保存记忆 ====================
                            saveToMemory(context, thinkingContent.toString(), fullAnswer.toString());

                            // ==================== 6. 异步 Judge 审计 ====================
                            asyncJudge(context, fullAnswer.toString());

                            sink.complete();
                        })
                        .doOnError(sink::error)
                        .subscribe();

            } catch (Exception e) {
                log.error("[SinglePass] 执行异常", e);
                sink.error(e);
            }
        });
    }

    // ==================== 私有方法 ====================

    /**
     * 构建消息列表
     */
    private List<Message> buildMessages(AgentContext context, String evidenceContext) {
        List<Message> messages = new ArrayList<>();

        // 1. System Prompt
        String systemPrompt = buildSystemPrompt(context);
        messages.add(new SystemMessage(systemPrompt));

        // 2. 加载历史记录 (带滑动窗口)
        List<Message> history = loadRecentHistory(context.getSessionId());
        if (!history.isEmpty()) {
            messages.addAll(history);
            log.debug("[SinglePass] 加载历史记录: 数量={}", history.size());
        }

        // 3. 预检索上下文 (如果有)
        if (StringUtils.hasText(evidenceContext)) {
            messages.add(new UserMessage(evidenceContext));
        }

        // 4. 用户原始问题
        messages.add(new UserMessage(context.getQuery()));

        return messages;
    }

    /**
     * 加载最近的历史消息
     * <p>从 ChatMemoryRepository 加载，并进行滑动窗口控制</p>
     */
    private List<Message> loadRecentHistory(String sessionId) {
        try {
            List<Message> allHistory = chatMemoryRepository.findByConversationId(sessionId);
            if (allHistory == null || allHistory.isEmpty()) {
                return new ArrayList<>();
            }

            // 滑动窗口控制 (保留最近 MEMORY_WINDOW_SIZE 条)
            if (allHistory.size() > MEMORY_WINDOW_SIZE) {
                log.debug("[SinglePass] 历史记录超过窗口大小: {} -> {} (保留最近{}条)",
                        allHistory.size(), MEMORY_WINDOW_SIZE, MEMORY_WINDOW_SIZE);
                return allHistory.subList(allHistory.size() - MEMORY_WINDOW_SIZE, allHistory.size());
            }

            return allHistory;

        } catch (Exception e) {
            log.error("[SinglePass] 加载历史记录失败: sessionId={}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存到记忆
     * <p>将用户问题和 Assistant 回复保存到 ChatMemoryRepository</p>
     */
    private void saveToMemory(AgentContext context, String thinking, String answer) {
        try {
            // 获取当前历史
            List<Message> history = chatMemoryRepository.findByConversationId(context.getSessionId());
            if (history == null) {
                history = new ArrayList<>();
            }

            // 添加用户问题
            history.add(new UserMessage(context.getQuery()));

            // 添加 Assistant 回复 (包含思考过程)
            String fullResponse = buildFullResponse(thinking, answer);
            history.add(new AssistantMessage(fullResponse));

            // 滑动窗口控制 (保存前截取)
            if (history.size() > MEMORY_WINDOW_SIZE) {
                history = history.subList(history.size() - MEMORY_WINDOW_SIZE, history.size());
            }

            // 保存
            chatMemoryRepository.saveAll(context.getSessionId(), history);
            log.debug("[SinglePass] 记忆已保存: sessionId={}, 消息数={}",
                    context.getSessionId(), history.size());

        } catch (Exception e) {
            log.error("[SinglePass] 保存记忆失败: sessionId={}", context.getSessionId(), e);
        }
    }

    /**
     * 构建完整回复 (包含思考过程)
     */
    private String buildFullResponse(String thinking, String answer) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(thinking)) {
            sb.append("```\n").append(thinking).append("\n```\n\n");
        }
        sb.append(answer != null ? answer : "");
        return sb.toString();
    }

    /**
     * 异步 Judge 审计
     */
    private void asyncJudge(AgentContext context, String answer) {
        // 使用独立线程异步执行，不阻塞主流程
        CompletableFuture.runAsync(() -> {
            try {
                // TODO: 构建 RAG 上下文并调用 Judge
                // EvaluationResult eval = reflectionService.evaluate(
                //     context.getQuery(),
                //     answer,
                //     buildRagContext(context)
                // );
                log.info("[SinglePass] Judge审计完成: sessionId={}", context.getSessionId());

            } catch (Exception e) {
                log.error("[SinglePass] Judge审计失败: sessionId={}", context.getSessionId(), e);
            }
        });
    }

    /**
     * 格式化知识库检索结果
     */
    private String formatKnowledgeResult(com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("【知识库检索结果】");

        // 优先使用 EvidenceBlock
        List<com.agenthub.api.ai.domain.knowledge.EvidenceBlock> blocks = result.evidenceBlocks();
        if (blocks != null && !blocks.isEmpty()) {
            sb.append("共找到 ").append(blocks.size())
                    .append(" 个证据块（").append(result.rawContentSnippets().size())
                    .append(" 个文档片段）\n\n");

            for (int i = 0; i < blocks.size(); i++) {
                com.agenthub.api.ai.domain.knowledge.EvidenceBlock block = blocks.get(i);
                sb.append(String.format("[证据 %d] %s | 支持度: %.2f\n",
                        i + 1, block.getSourceReference(), block.supportScore()));
                sb.append(block.content()).append("\n\n");
            }
        } else {
            // 降级：使用 rawContentSnippets
            sb.append("共找到 ").append(result.rawContentSnippets().size())
                    .append(" 条相关内容\n\n");
            for (String snippet : result.rawContentSnippets()) {
                sb.append(snippet).append("\n\n---\n\n");
            }
        }

        // 来源文件列表
        if (result.sources() != null && !result.sources().isEmpty()) {
            sb.append("【来源文件】\n");
            for (var source : result.sources()) {
                sb.append(String.format("- %s\n", source.filename()));
            }
        }

        return sb.toString();
    }


    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(AgentContext context) {
        try {
            // Native Function Calling 模式下，不需要在 System Prompt 里塞工具描述
            // 但为了兼容旧的模板变量，我们传一个空字符串
            String toolsDesc = "";

            // 准备模板变量
            Map<String, Object> vars = new HashMap<>();
            vars.put("tools_desc", toolsDesc);

            // 渲染模板
            String systemPromptText = sysPromptService.render(SYSTEM_PROMPT_CODE, vars);

            if (systemPromptText != null && !systemPromptText.isEmpty()) {
                return systemPromptText;
            }

            // 降级方案
            return "你是一个电力行业智能助手。";

        } catch (Exception e) {
            log.warn("[SinglePass] 构建系统提示词失败", e);
            return "你是一个智能助手。";
        }
    }

    /**
     * 流式 LLM 调用
     * <p>使用 DashScopeNativeService 获取深度思考模型的流式输出</p>
     *
     * @param messages 消息列表
     * @param context  Agent 上下文
     * @return 流式响应 (包含思考过程和最终回答)
     */
    private Flux<String> doStreamChat(List<Message> messages, AgentContext context) {
        // 转换为 DashScope 格式
        List<com.alibaba.dashscope.common.Message> dashMessages = convertToDashScopeMessages(messages);

        // 准备工具列表
        Set<String> excludedTools = new HashSet<>();
        // 如果预检索已完成，排除 knowledge_search
        if (context.isPreRetrievalDone()) {
            excludedTools.add("knowledge_search");
        }
        
        // 获取业务工具列表 (AgentTool)
        List<AgentTool> tools = toolRegistry.getTools(excludedTools);
        log.debug("[SinglePass] 调用 LLM，携带工具数量: {}", tools.size());

        return Flux.create(sink -> {
            final boolean[] state = new boolean[]{false, false}; // [0]=思考已开始, [1]=思考已结束

            nativeService.deepThinkStream(WORKER_MODEL, dashMessages, tools, new StreamCallback() {
                @Override
                public void onReasoning(String reasoning) {
                    if (!state[0]) {
                        sink.next("@@THINK_START@@");
                        state[0] = true;
                    }
                    if (reasoning != null && !reasoning.isEmpty()) {
                        sink.next(reasoning);
                    }
                }

                @Override
                public void onContent(String content) {
                    if (state[0] && !state[1]) {
                        sink.next("@@THINK_END@@");
                        state[1] = true;
                    }
                    if (content != null && !content.isEmpty()) {
                        sink.next(content);
                    }
                }

                @Override
                public void onToolCall(List<ToolCall> toolCalls) {
                    // 当模型决定调用工具时
                    if (state[0] && !state[1]) {
                        sink.next("@@THINK_END@@");
                        state[1] = true;
                    }

                    if (toolCalls == null || toolCalls.isEmpty()) {
                        log.warn("[SinglePass] 工具调用列表为空");
                        return;
                    }

                    for (ToolCall tc : toolCalls) {
                        if (tc == null || tc.toolName() == null) {
                            log.warn("[SinglePass] 跳过无效的工具调用: tc={}", tc);
                            continue;
                        }
                        log.info("[SinglePass] 模型触发工具调用: toolName={}, parameters={}",
                                tc.toolName(), tc.parameters());
                        // 格式化输出给前端
                        String toolCallMsg = String.format("\n\n> 🛠️ **系统调用工具**: `%s`\n> 参数: `%s`\n",
                            tc.toolName(), tc.parameters() != null ? tc.parameters() : "");
                        sink.next(toolCallMsg);
                    }
                }

                @Override
                public void onComplete() {
                    // 确保结束标记已发送
                    if (state[0] && !state[1]) {
                        sink.next("@@THINK_END@@");
                    }
                    sink.complete();
                }

                @Override
                public void onError(Throwable e) {
                    log.error("[SinglePass] LLM流式调用失败: sessionId={}", context.getSessionId(), e);
                    sink.error(e);
                }
            });
        });
    }

    /**
     * 转换 Spring AI 消息为 DashScope 消息
     */
    private List<com.alibaba.dashscope.common.Message> convertToDashScopeMessages(
            List<Message> springMessages) {
        return springMessages.stream().map(msg -> {
            com.alibaba.dashscope.common.Message.MessageBuilder builder =
                    com.alibaba.dashscope.common.Message.builder();

            if (msg instanceof SystemMessage) {
                builder.role(com.alibaba.dashscope.common.Role.SYSTEM.getValue());
            } else if (msg instanceof UserMessage) {
                builder.role(com.alibaba.dashscope.common.Role.USER.getValue());
            } else if (msg instanceof AssistantMessage) {
                builder.role(com.alibaba.dashscope.common.Role.ASSISTANT.getValue());
            } else {
                builder.role(com.alibaba.dashscope.common.Role.USER.getValue());
            }

            builder.content(msg.getText());
            return builder.build();
        }).collect(Collectors.toList());
    }
}
