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
import com.agenthub.api.prompt.builder.CaseSnapshotBuilder;
import com.agenthub.api.prompt.enums.CaseStatus;
import com.agenthub.api.prompt.enums.Scenario;
import com.agenthub.api.prompt.service.ICaseSnapshotService;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static net.sf.jsqlparser.parser.feature.Feature.set;

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
 * │  2. 预检索 (PreRetrieval) - 仅 KB_QA                                │
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
    private final ICaseSnapshotService caseSnapshotService;
    private final ObjectMapper objectMapper;
    private final DashScopeNativeService nativeService;

    // ==================== 线程池 ====================

    /** Judge 审计专用线程池 */
    private final Executor judgeExecutor;

    /** Agent 工作线程池 (用于工具执行等 IO 密集型任务) */
    private final Executor agentWorkerExecutor;

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
    private static final String SYSTEM_PROMPT_CODE = "SYSTEM-RAG-LITE";

    // ==================== 构造器 ====================

    public SinglePassExecutor(
            ChatClient workerClient,
            IntentRecognitionService intentRecognition,
            PowerKnowledgeService powerKnowledgeService,
            ToolRegistry toolRegistry,
            ReflectionService reflectionService,
            ChatMemoryRepository chatMemoryRepository,
            ISysPromptService sysPromptService,
            ICaseSnapshotService caseSnapshotService,
            ObjectMapper objectMapper,
            DashScopeNativeService nativeService,
            Executor judgeExecutor,
            Executor agentWorkerExecutor) {
        this.workerClient = workerClient;
        this.intentRecognition = intentRecognition;
        this.powerKnowledgeService = powerKnowledgeService;
        this.toolRegistry = toolRegistry;
        this.reflectionService = reflectionService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.sysPromptService = sysPromptService;
        this.caseSnapshotService = caseSnapshotService;
        this.objectMapper = objectMapper;
        this.nativeService = nativeService;
        this.judgeExecutor = judgeExecutor;
        this.agentWorkerExecutor = agentWorkerExecutor;
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

                // ==================== 2. 预检索 (仅 KB_QA) ====================
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

                        // v2.2 新增：保存 sources 到 context，供前端渲染
                        if (knowledgeResult.sources() != null && !knowledgeResult.sources().isEmpty()) {
                            List<AgentContext.SourceDocument> sources = knowledgeResult.sources().stream()
                                    .map(src -> AgentContext.SourceDocument.builder()
                                            .filename(src.filename())
                                            .downloadUrl(src.downloadUrl())
                                            .build())
                                    .toList();
                            context.setSources(sources);
                            log.info("[SinglePass] 保存 sources 到 context: {} 个文件", sources.size());
                        }

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
                            // 直接透传所有内容，包括 @@THINK_START@@ 和 @@THINK_END@@ 标签
                            // Controller 会根据这些标签来区分思考内容和正文
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
                            log.info("[SinglePass] 开始异步 Judge 审计: sessionId={}, answerLength={}",
                                    context.getSessionId(), fullAnswer.length());
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
     * <p>注意：会过滤掉历史消息中的 @@THINK_START@@ 和 @@THINK_END@@ 标签，
     * 防止模型在后续回答中模仿这些内部标记</p>
     */
    private List<Message> loadRecentHistory(String sessionId) {
        try {
            List<Message> allHistory = chatMemoryRepository.findByConversationId(sessionId);
            if (allHistory == null || allHistory.isEmpty()) {
                return new ArrayList<>();
            }

            // 过滤掉历史消息中的思考标签，防止模型模仿
            List<Message> filteredHistory = new ArrayList<>();
            for (Message msg : allHistory) {
                if (msg instanceof AssistantMessage assistantMsg) {
                    String originalContent = assistantMsg.getText();
                    if (originalContent != null) {
                        // 移除 @@THINK_START@@ ... @@THINK_END@@ 标签及其内容
                        // 这样思考过程不会传给模型，但模型仍然可以基于之前的正式回答继续对话
                        String filteredContent = originalContent.replaceAll("@@THINK_START@@.*?@@THINK_END@@", "").trim();
                        // 创建新的 AssistantMessage，保留原消息的其他属性（如 metadata）
                        filteredHistory.add(new AssistantMessage(filteredContent));
                    } else {
                        filteredHistory.add(assistantMsg);
                    }
                } else {
                    // UserMessage 和其他类型消息保持原样
                    filteredHistory.add(msg);
                }
            }

            // 滑动窗口控制 (保留最近 MEMORY_WINDOW_SIZE 条)
            if (filteredHistory.size() > MEMORY_WINDOW_SIZE) {
                log.debug("[SinglePass] 历史记录超过窗口大小: {} -> {} (保留最近{}条)",
                        filteredHistory.size(), MEMORY_WINDOW_SIZE, MEMORY_WINDOW_SIZE);
                return filteredHistory.subList(filteredHistory.size() - MEMORY_WINDOW_SIZE, filteredHistory.size());
            }

            return filteredHistory;

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
     * <p>
     * 1. 构建审计上下文（query, answer, preRetrievedContent, toolCallRecords, 历史记录）
     * 2. 调用 ReflectionService.evaluate() 进行 AI 审计
     * 3. 构建 CaseSnapshot 并冻结到数据库
     * </p>
     */
    private void asyncJudge(AgentContext context, String answer) {
        // 使用 Judge 专用线程池异步执行，不阻塞主流程
        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                log.info("[Judge] ========== 开始审计 ==========");

                // ==================== 1. 构建审计上下文 ====================
                Map<String, Object> ragContext = buildRagContextForJudge(context);

                log.info("[Judge] 审计上下文构建完成: toolCalls={}, hasPreRetrieved={}, hasHistory={}",
                        context.hasToolCallRecords() ? context.getToolCallRecords().size() : 0,
                        ragContext.containsKey("pre_retrieved_content"),
                        ragContext.containsKey("conversation_history"));

                // ==================== 2. 调用 ReflectionService 执行审计 ====================
                EvaluationResult evalResult = reflectionService.evaluate(
                        context.getQuery(),
                        answer,
                        ragContext
                );

                log.info("[Judge] 审计结果: sessionId={}, passed={}, reason='{}'",
                        context.getSessionId(),
                        evalResult.isPassed(),
                        evalResult.getReason());

                // ==================== 3. 构建并冻结 CaseSnapshot ====================
                freezeCaseSnapshot(context, answer, evalResult, startTime);

                log.info("[Judge] ========== 审计完成 ==========");

            } catch (Exception e) {
                log.error("[Judge] 审计失败: sessionId={}", context.getSessionId(), e);
                // 审计失败也要尝试保存 CaseSnapshot（标记为失败状态）
                try {
                    freezeFailedCaseSnapshot(context, answer, e);
                } catch (Exception ex) {
                    log.error("[Judge] 保存失败 CaseSnapshot 也失败了", ex);
                }
            }
        }, judgeExecutor);
    }

    /**
     * 构建 Judge 审计上下文
     */
    private Map<String, Object> buildRagContextForJudge(AgentContext context) {
        Map<String, Object> ragContext = new HashMap<>();

        // 1. 预检索内容（最重要）
        if (context.getPreRetrievedContent() != null) {
            ragContext.put("pre_retrieved_content", context.getPreRetrievedContent());
        }

        // 2. 工具调用记录（列表格式，适配 FreeMarker 模板）
        if (context.hasToolCallRecords()) {
            List<Map<String, Object>> toolCallsList = new ArrayList<>();
            for (ToolCallRecord record : context.getToolCallRecords()) {
                Map<String, Object> tcMap = new HashMap<>();
                if (record.toolCall() != null) {
                    tcMap.put("tool_name", record.toolCall().toolName());
                    tcMap.put("parameters", record.toolCall().parameters());
                }
                if (record.toolResult() != null) {
                    tcMap.put("success", record.toolResult().success());
                    tcMap.put("result", record.toolResult().result());
                    tcMap.put("error", record.toolResult().errorMessage());
                    tcMap.put("duration_ms", record.toolResult().durationMs());
                }
                toolCallsList.add(tcMap);
            }
            ragContext.put("tool_calls", toolCallsList);
        }

        // 3. 意图信息
        if (context.getIntent() != null) {
            ragContext.put("intent", context.getIntent().name());
        }
        if (context.getIntentConfidence() != null) {
            ragContext.put("intent_confidence", context.getIntentConfidence());
        }

        // 4. 最近的历史记录（2-3轮，即6条消息）
        try {
            List<Message> history = chatMemoryRepository.findByConversationId(context.getSessionId());
            if (history != null && !history.isEmpty()) {
                int maxMessages = Math.min(6, history.size());
                List<Message> recentHistory = history.subList(
                        Math.max(0, history.size() - maxMessages),
                        history.size()
                );
                StringBuilder historySummary = new StringBuilder();
                for (Message msg : recentHistory) {
                    String role = msg instanceof UserMessage ? "用户" : "助手";
                    String content = msg.getText();
                    if (content != null && content.length() > 200) {
                        content = content.substring(0, 200) + "...";
                    }
                    historySummary.append(String.format("[%s]: %s\n", role, content));
                }
                ragContext.put("conversation_history", historySummary.toString());
            }
        } catch (Exception e) {
            log.warn("[SinglePass] 获取历史记录失败: {}", e.getMessage());
        }

        return ragContext;
    }

    /**
     * 冻结 CaseSnapshot 到数据库
     */
    private void freezeCaseSnapshot(AgentContext context, String answer,
                                     EvaluationResult evalResult, long startTime) {
        try {
            log.info("[CaseSnapshot] 开始冻结: sessionId={}, passed={}",
                    context.getSessionId(), evalResult.isPassed());

            // 使用 Builder 构建 CaseSnapshot
            var snapshot = CaseSnapshotBuilder.create()
                    .scenario(Scenario.CHAT)
                    .intent(context.getIntent() != null ? context.getIntent().name() : "UNKNOWN")
                    .input(context.getQuery(),
                            context.getUserId() != null ? Long.parseLong(context.getUserId()) : null,
                            context.getSessionId())
                    .outputData(answer, System.currentTimeMillis() - startTime, null)
                     .status(CaseStatus.COMPLETED)
                    .durationMs((int) (System.currentTimeMillis() - startTime))
                    .toolCallRecords(context.getToolCallRecords())
                    .aiJudgeResult(evalResult.isPassed(), evalResult.getReason(), "SYSTEM-JUDGE-v1.0")
                    .promptData("SYSTEM-JUDGE-v1.0")
                    .metadata("intent_confidence",
                            context.getIntentConfidence() != null ?
                                    String.valueOf(context.getIntentConfidence()) : "N/A")
                    .build();

            // 异步保存到数据库
            caseSnapshotService.freezeAsync(snapshot);

            log.info("[CaseSnapshot] 已提交冻结: sessionId={}", context.getSessionId());

        } catch (Exception e) {
            log.error("[CaseSnapshot] 构建失败", e);
        }
    }

    /**
     * 冻结失败状态的 CaseSnapshot
     */
    private void freezeFailedCaseSnapshot(AgentContext context, String answer, Exception error) {
        var snapshot = CaseSnapshotBuilder.create()
                .scenario(Scenario.CHAT)
                .intent(context.getIntent() != null ? context.getIntent().name() : "UNKNOWN")
                .input(context.getQuery(),
                        context.getUserId() != null ? Long.parseLong(context.getUserId()) : null,
                        context.getSessionId())
                .outputData(answer, null, null)
                .status(CaseStatus.FAILED)
                .errorMessage("Judge审计失败: " + error.getMessage())
                .toolCallRecords(context.getToolCallRecords())
                .build();

        caseSnapshotService.freezeAsync(snapshot);
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
     * <p>支持工具调用：当 LLM 决定调用工具时，会执行工具并基于结果生成最终回答</p>
     *
     * @param messages 消息列表
     * @param context  Agent 上下文
     * @return 流式响应 (包含思考过程和最终回答)
     */
    private Flux<String> doStreamChat(List<Message> messages, AgentContext context) {
        // 转换为 DashScope 格式
        List<com.alibaba.dashscope.common.Message> dashMessages = convertToDashScopeMessages(messages);

        // 准备工具列表
        List<AgentTool> tools;
        if (context.isPreRetrievalDone()) {
            // 预检索已完成：答案已在上下文中，不需要任何工具
            tools = List.of();
            log.debug("[SinglePass] 预检索已完成，不传任何工具给 LLM");
        } else {
            // 预检索未完成：传递所有工具
            tools = toolRegistry.getTools(Set.of());
            log.debug("[SinglePass] 调用 LLM，携带工具数量: {}", tools.size());
        }

        return Flux.create(sink -> {
            final boolean[] state = new boolean[]{false, false}; // [0]=思考已开始, [1]=思考已结束
            final List<ToolCall> pendingToolCalls = new ArrayList<>();
            final boolean[] toolCallTriggered = new boolean[]{false};

            nativeService.deepThinkStream(WORKER_MODEL, dashMessages, tools, new StreamCallback() {
                @Override
                public void onReasoning(String reasoning) {
                    // DashScope API 已将 reasoningContent 和 content 分离
                    // 直接信任 API 的分离结果，不做额外过滤
                    if (reasoning != null && !reasoning.isEmpty()) {
                        if (!state[0]) {
                            sink.next("@@THINK_START@@");
                            state[0] = true;
                        }
                        sink.next(reasoning);
                    }
                }

                @Override
                public void onContent(String content) {
                    // DashScope API 已将 reasoningContent 和 content 分离
                    // 直接信任 API 的分离结果，不做额外过滤
                    if (content != null && !content.isEmpty()) {
                        // 正式内容：先结束思考区域（如果还未结束）
                        if (state[0] && !state[1]) {
                            sink.next("@@THINK_END@@");
                            state[1] = true;
                        }
                        sink.next(content);
                    }
                }

                @Override
                public void onToolCall(List<ToolCall> toolCalls) {
                    if (toolCalls == null || toolCalls.isEmpty()) {
                        return;
                    }
                    // 记录工具调用
                    pendingToolCalls.addAll(toolCalls);
                    toolCallTriggered[0] = true;

                    for (ToolCall tc : toolCalls) {
                        if (tc != null && tc.toolName() != null) {
                            log.info("[SinglePass] LLM 调用工具: toolName={}, parameters={}",
                                    tc.toolName(), tc.parameters());
                            sink.next(String.format("\n\n> 🛠️ 调用工具: `%s`\n", tc.toolName()));
                        }
                    }
                }

                @Override
                public void onComplete() {
                    // 确保结束标记已发送
                    if (state[0] && !state[1]) {
                        sink.next("@@THINK_END@@");
                    }

                    // 如果有工具调用需要执行，执行工具并继续
                    if (toolCallTriggered[0] && !pendingToolCalls.isEmpty()) {
                        executeToolsAndContinue(pendingToolCalls, messages, context, sink, state);
                    } else {
                        sink.complete();
                    }
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
     * 执行工具调用并基于结果继续生成
     */
    private void executeToolsAndContinue(
            List<ToolCall> toolCalls,
            List<Message> originalMessages,
            AgentContext context,
            reactor.core.publisher.FluxSink<String> sink,
            boolean[] state) {

        log.info("[SinglePass] 开始执行工具，数量: {}", toolCalls.size());

        // 关键点：在主线程捕获 SecurityContext，防止异步线程丢失认证信息
        SecurityContext mainThreadSecurityContext = SecurityContextHolder.getContext();

        // 使用 Agent 工作线程池异步执行工具
        CompletableFuture.runAsync(() -> {
            // 在异步线程中设置 SecurityContext
            SecurityContextHolder.setContext(mainThreadSecurityContext);

            Map<String, String> toolResults = new LinkedHashMap<>();
            boolean hasFailure = false;

            try {
                for (ToolCall tc : toolCalls) {
                    if (tc == null || tc.toolName() == null) {
                        continue;
                    }

                    String toolName = tc.toolName();
                    String parameters = tc.parameters() != null ? tc.parameters() : "{}";

                    try {
                        AgentTool tool = toolRegistry.getTool(toolName);
                        if (tool == null) {
                            String errorMsg = String.format("工具 '%s' 不存在", toolName);
                            sink.next("\n\n" + errorMsg + "\n");
                            toolResults.put(toolName, errorMsg);
                            hasFailure = true;
                            continue;
                        }

                        // 解析参数
                        Map<String, Object> argsMap = parseParameters(parameters);

                        // 执行工具
                        ToolExecutionRequest request = ToolExecutionRequest.builder()
                                .toolName(toolName)
                                .arguments(argsMap)
                                .originalCallId(tc.callId())
                                .build();

                        long execStart = System.currentTimeMillis();
                        ToolExecutionResult result = tool.execute(request, context);
                        long execDuration = System.currentTimeMillis() - execStart;

                        if (result.isSuccess()) {
                            toolResults.put(toolName, result.getOutput() != null ? result.getOutput() : "");
                            // 记录成功的工具调用
                            context.addToolCallRecord(ToolCallRecord.of(
                                    tc,
                                    ToolResult.success(toolName, result.getOutput(), execDuration)
                            ));
                        } else {
                            String errorMsg = String.format("工具执行失败: %s",
                                    result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误");
                            sink.next("\n\n" + errorMsg + "\n");
                            toolResults.put(toolName, errorMsg);
                            hasFailure = true;
                            // 记录失败的工具调用
                            context.addToolCallRecord(ToolCallRecord.of(
                                    tc,
                                    ToolResult.failure(toolName, result.getErrorMessage(), execDuration, tc.callId())
                            ));
                        }

                    } catch (Exception e) {
                        log.error("[SinglePass] 工具执行异常: toolName={}", toolName, e);
                        String errorMsg = String.format("工具执行异常: %s", e.getMessage());
                        sink.next("\n\n" + errorMsg + "\n");
                        toolResults.put(toolName, errorMsg);
                        hasFailure = true;
                        // 记录异常的工具调用
                        context.addToolCallRecord(ToolCallRecord.of(
                                tc,
                                ToolResult.failure(toolName, e.getMessage(), 0, tc.callId())
                        ));
                    }
                }

                // 工具执行失败时，直接结束流程，不要让 LLM 继续编造
                if (hasFailure) {
                    sink.next("\n\n【系统提示】工具执行失败，请稍后重试或联系管理员。\n");
                    sink.complete();
                    return;
                }

                // 所有工具都成功执行，继续生成最终回答
                continueAfterTools(originalMessages, toolResults, context, sink, state);

            } finally {
                // 清理异步线程的 SecurityContext
                SecurityContextHolder.clearContext();
            }

        }, agentWorkerExecutor).exceptionally(e -> {
            log.error("[SinglePass] 工具执行异步异常", e);
            sink.next("\n\n【系统错误】工具执行过程中发生异常\n");
            sink.complete();
            return null;
        });
    }

    /**
     * 工具执行完成后，继续调用 LLM 生成最终回答
     */
    private void continueAfterTools(
            List<Message> originalMessages,
            Map<String, String> toolResults,
            AgentContext context,
            reactor.core.publisher.FluxSink<String> sink,
            boolean[] state) {

        log.info("[SinglePass] 工具执行完成，生成最终回答");

        // 构建新的消息列表
        List<Message> newMessages = new ArrayList<>(originalMessages);

        // 添加工具结果
        StringBuilder toolResultsMsg = new StringBuilder("【工具执行结果】\n");
        for (Map.Entry<String, String> entry : toolResults.entrySet()) {
            String result = entry.getValue();
            if (result.length() > 5000) {
                result = result.substring(0, 5000) + "\n\n...(结果过长，已截断)";
            }
            toolResultsMsg.append(String.format("**%s**: %s\n\n", entry.getKey(), result));
        }
        newMessages.add(new UserMessage(toolResultsMsg.toString()));

        // 转换为 DashScope 格式
        List<com.alibaba.dashscope.common.Message> dashMessages = convertToDashScopeMessages(newMessages);

        // 关键修复：第二轮 LLM 调用使用新的 state，不复用第一轮的 state
        // 每一轮 LLM 调用都是独立的思考过程，应该有独立的状态标记
        final boolean[] newState = new boolean[]{false, false}; // [0]=思考已开始, [1]=思考已结束
        // 收集第二轮 LLM 生成的完整回答，用于 Judge 审计
        final StringBuilder secondRoundAnswer = new StringBuilder();

        nativeService.deepThinkStream(WORKER_MODEL, dashMessages, List.of(), new StreamCallback() {
            @Override
            public void onReasoning(String reasoning) {
                // DashScope API 已将 reasoningContent 和 content 分离
                // 直接信任 API 的分离结果，不做额外过滤
                if (reasoning != null && !reasoning.isEmpty()) {
                    if (!newState[0]) {
                        sink.next("@@THINK_START@@");
                        newState[0] = true;
                    }
                    sink.next(reasoning);
                    secondRoundAnswer.append(reasoning);
                }
            }

            @Override
            public void onContent(String content) {
                // DashScope API 已将 reasoningContent 和 content 分离
                // 直接信任 API 的分离结果，不做额外过滤
                if (content != null && !content.isEmpty()) {
                    // 正式内容：先结束思考区域（如果还未结束）
                    if (newState[0] && !newState[1]) {
                        sink.next("@@THINK_END@@");
                        newState[1] = true;
                    }
                    sink.next(content);
                    secondRoundAnswer.append(content);
                }
            }

            @Override
            public void onToolCall(List<ToolCall> toolCalls) {
                log.warn("[SinglePass] 第二轮 LLM 不应再调用工具");
            }

            @Override
            public void onComplete() {
                // 确保结束标记已发送
                if (newState[0] && !newState[1]) {
                    sink.next("@@THINK_END@@");
                }

                // ========== 关键修复：第二轮 LLM 完成后也要调用 asyncJudge ==========
                log.info("[SinglePass] 第二轮 LLM 生成完成，回答长度: {}", secondRoundAnswer.length());

                // 保存记忆（第二轮的回答）
                saveToMemory(context, "", secondRoundAnswer.toString());

                // 异步 Judge 审计
                asyncJudge(context, secondRoundAnswer.toString());

                sink.complete();
            }

            @Override
            public void onError(Throwable e) {
                log.error("[SinglePass] 第二轮 LLM 调用失败", e);
                sink.error(e);
            }
        });
    }

    /**
     * 解析工具参数
     */
    private Map<String, Object> parseParameters(String parameters) {
        try {
            if (parameters == null || parameters.isBlank()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(parameters,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[SinglePass] 解析工具参数失败: {}, error={}", parameters, e.getMessage());
            return new HashMap<>();
        }
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
