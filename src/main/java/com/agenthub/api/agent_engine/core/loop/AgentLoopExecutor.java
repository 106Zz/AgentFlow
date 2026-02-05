package com.agenthub.api.agent_engine.core.loop;

import com.agenthub.api.agent_engine.capability.ToolRegistry;
import com.agenthub.api.agent_engine.config.DashScopeNativeService;
import com.agenthub.api.agent_engine.model.*;
import com.agenthub.api.agent_engine.service.ReflectionService;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.domain.llm.DeepThinkResult;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent 循环执行器
 * <p>核心循环引擎，实现 "思考 → 行动 → 反思" 的完整循环流程</p>
 *
 * <h3>执行流程：</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    AgentLoopExecutor                         │
 * ├─────────────────────────────────────────────────────────────┤
 * │  1. 快速思考 (QuickThink): 判断是否需要工具调用               │
 * │  2. 工具执行 (Execute): 如需工具，执行并获取结果              │
 * │  3. 深度思考 (DeepThink): 生成最终回答                       │
 * │  4. 反思评估 (Reflect): 评估回答质量                         │
 * │  5. 循环判断: 根据评估结果决定是否继续下一轮                  │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>设计特点：</h3>
 * <ul>
 *   <li><b>智能循环</b>: 评估不通过时自动进入下一轮，最多执行 maxRounds 轮</li>
 *   <li><b>超时保护</b>: 整体执行时间超过 timeoutMs 时自动终止</li>
 *   <li><b>上下文累积</b>: 每轮的反馈会传递到下一轮，帮助改进回答</li>
 *   <li><b>安全上下文</b>: 正确处理 Spring Security 的上下文传递</li>
 * </ul>
 *
 * @author AgentHub
 * @since 2026-02-03
 */
@Slf4j
public class AgentLoopExecutor {

    // ==================== 依赖服务 ====================

    /**
     * DashScope 原生服务
     * <p>用于调用深度思考模型（如 DeepSeek R1）</p>
     */
    private final DashScopeNativeService nativeService;

    /**
     * 工具注册中心
     * <p>预构建的工具描述缓存和索引</p>
     */
    private final ToolRegistry toolRegistry;

    /**
     * 反思服务
     * <p>评估回答质量，提供改进建议</p>
     */
    private final ReflectionService reflectionService;

    /**
     * JSON 序列化工具
     * <p>用于解析工具调用参数</p>
     */
    private final ObjectMapper objectMapper;

    /**
     * 聊天记忆仓库
     * <p>加载历史聊天记录</p>
     */
    private final ChatMemoryRepository chatMemoryRepository;

    /**
     * Agent 工作线程池
     * <p>异步执行循环任务</p>
     */
    private final ThreadPoolTaskExecutor agentWorkerExecutor;

    /**
     * 系统提示词服务
     * <p>渲染动态系统提示词</p>
     */
    private final ISysPromptService sysPromptService;

    // ==================== 常量配置 ====================

    /**
     * JSON 块提取正则
     * <p>从 LLM 输出中提取 JSON 格式的工具调用</p>
     */
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

    /**
     * 系统提示词模板代码
     */
    private static final String SYSTEM_PROMPT_CODE = "SYSTEM-RAG-v1.0";

    /**
     * 工作模型
     * <p>用于快速思考和深度思考的模型</p>
     */
    private static final String WORKER_MODEL = "deepseek-v3.2";

    // ==================== 构造器 ====================

    /**
     * 构造 Agent 循环执行器
     *
     * @param nativeService         DashScope 原生服务
     * @param toolRegistry          工具注册中心
     * @param reflectionService     反思服务
     * @param objectMapper          JSON 序列化工具
     * @param chatMemoryRepository  聊天记忆仓库
     * @param agentWorkerExecutor   Agent 工作线程池
     * @param sysPromptService      系统提示词服务
     */
    public AgentLoopExecutor(
            DashScopeNativeService nativeService,
            ToolRegistry toolRegistry,
            ReflectionService reflectionService,
            ObjectMapper objectMapper,
            ChatMemoryRepository chatMemoryRepository,
            ThreadPoolTaskExecutor agentWorkerExecutor,
            ISysPromptService sysPromptService) {
        this.nativeService = nativeService;
        this.toolRegistry = toolRegistry;
        this.reflectionService = reflectionService;
        this.objectMapper = objectMapper;
        this.chatMemoryRepository = chatMemoryRepository;
        this.agentWorkerExecutor = agentWorkerExecutor;
        this.sysPromptService = sysPromptService;
    }

    // ==================== 核心执行方法 ====================

    /**
     * 流式执行循环（重构版：一切皆消息架构）
     * <p>实现 "思考 → 行动 → 反思" 的流式循环，基于动态增长的消息历史</p>
     *
     * <h3>核心思想：一切皆消息</h3>
     * <p>工具调用的结果、反思的批评，都作为消息追加到历史中，形成完整的上下文链。</p>
     * <p>LLM 在每轮都能看到完整的故事：用户问题 → 我的思考 → 我调的工具 → 工具返回 → 批评反馈</p>
     *
     * <h3>执行流程：</h3>
     * <pre>
     * ┌─────────────────────────────────────────────────────────────┐
     * │                    Dynamic Context Flow                     │
     * ├─────────────────────────────────────────────────────────────┤
     * │  初始化: chatHistory = [System, User(query)]                │
     * │                                                             │
     * │  Round 1:                                                   │
     * │    → QuickThink(chatHistory) → 决策：需要工具              │
     * │    → ExecuteTool → 结果追加到 chatHistory                   │
     * │    → DeepThink(chatHistory) → 生成回答                      │
     * │    → Reflect → 失败                                         │
     * │    → 批评追加到 chatHistory → 现在历史包含完整链路            │
     * │                                                             │
     * │  Round 2 (自动修正):                                        │
     * │    → QuickThink(chatHistory) → 看到批评，决策：重调工具      │
     * │    → ExecuteTool(修正参数) → 新结果追加到历史               │
     * │    → DeepThink(chatHistory) → 基于完整链路生成回答          │
     * │    → Reflect → 通过 → 输出最终回答                          │
     * └─────────────────────────────────────────────────────────────┘
     * </pre>
     *
     * @param context Agent 上下文
     * @return 结果流
     */
    public Flux<String> executeStream(AgentContext context) {
        LoopContext initialContext = LoopContext.fromAgentContext(context);
        log.info("[LoopExecutor] 初始上下文: {}", initialContext);
        SecurityContext securityContext = SecurityContextHolder.getContext();

        return Flux.create(sink -> {
            try {
                SecurityContextHolder.setContext(securityContext);
                LoopContext currentContext = initialContext;

                // ==================== 核心：动态消息历史 ====================
                // 这是一个不断增长的列表，记录整个对话过程
                List<org.springframework.ai.chat.messages.Message> chatHistory = new ArrayList<>();

                // 记录最后一轮生成的答案（用于超时或其他异常情况下的降级发送）
                AtomicReference<String> lastGeneratedAnswer = new AtomicReference<>();
                AtomicReference<String> lastThoughtContent = new AtomicReference<>();

                // 记录当前轮的工具调用信息，用于构建 RoundRecord
                List<ToolCall> currentRoundToolCalls = new ArrayList<>();
                List<ToolResult> currentRoundToolResults = new ArrayList<>();
                String currentRoundQuickThought = null;

                // 1. 添加系统提示词
                String systemPrompt = buildSystemPrompt(currentContext);
                chatHistory.add(new SystemMessage(systemPrompt));

                // 2. 添加用户原始问题
                chatHistory.add(new UserMessage(context.getQuery()));

                log.info("[LoopExecutor] 开始循环，maxRounds={}", currentContext.maxRounds());

                // ==================== 多轮循环 ====================
                int loopIteration = 0;
                while (true) {
                    loopIteration++;

                    // 超时检查
                    if (currentContext.isTimeout()) {
                        log.warn("[LoopExecutor] 超时，已用时 {}ms", currentContext.getElapsedMs());
                        // 超时时，如果有最后一轮的答案，尝试发送
                        String lastAnswer = lastGeneratedAnswer.get();
                        if (StringUtils.hasText(lastAnswer)) {
                            log.info("[LoopExecutor] 超时降级：发送最后一轮生成的答案，长度={}", lastAnswer.length());
                            sink.next(lastAnswer);
                        }
                        break;
                    }

                    // 进入下一轮（在循环开始时递增）
                    currentContext = currentContext.nextRound();
                    log.info("[LoopExecutor] ===== 循环迭代 #{}, 进入 Round {}/{} =====",
                        loopIteration, currentContext.currentRound(), currentContext.maxRounds());
                    log.info("[LoopExecutor] chatHistory大小: {}", chatHistory.size());

                    // 检查是否超过最大轮数（在执行 Round 逻辑之前）
                    if (currentContext.currentRound() > currentContext.maxRounds()) {
                        log.info("[LoopExecutor] 已达到最大轮数 {}，退出循环", currentContext.maxRounds());
                        break;
                    }

                    // ==================== 步骤 1: 快速思考 (Router/Planner) ====================
                    // 基于当前的完整历史记录，决定是"调工具"还是"直接回答"
                    log.info("[LoopExecutor] [Round-{}] 开始调用 quickThink...", currentContext.currentRound());
                    QuickThinkResult quickResult;
                    try {
                        quickResult = quickThink(chatHistory, currentContext);
                        log.info("[LoopExecutor] [Round-{}] quickThink 完成: decision={}, needsToolCall={}, thoughtContent长度={}",
                            currentContext.currentRound(), quickResult.decision(),
                            quickResult.needsToolCall(),
                            quickResult.thoughtContent() != null ? quickResult.thoughtContent().length() : 0);
                    } catch (Exception e) {
                        log.error("[LoopExecutor] [Round-{}] quickThink 异常!", currentContext.currentRound(), e);
                        // 降级处理：返回不需要调工具的结果
                        quickResult = new QuickThinkResult(
                            "思考异常: " + e.getMessage(),
                            "DIRECT_ANSWER",
                            false, null, new HashMap<>(), null, List.of("异常处理")
                        );
                    }

                    // 实时输出思考过程
                    String thoughtContent = quickResult.thoughtContent();
                    if (StringUtils.hasText(thoughtContent)) {
                        sink.next("@@THINK_START@@");
                        sink.next(thoughtContent);
                        sink.next("@@THINK_END@@");
                    }

                    // 记录当前轮的快速思考内容（用于构建 RoundRecord）
                    currentRoundQuickThought = thoughtContent;

                    // ==================== 步骤 2: 动态分支 ====================
                    if (quickResult.needsToolCall()) {
                        // --- 分支 A: 需要调用工具 ---
                        String toolName = quickResult.toolName();
                        Map<String, Object> toolArgs = quickResult.toolArgs();

                        // 【问题 2 修复】检查工具是否已在之前轮次调用过
                        // 使用 LoopContext.roundHistory 中的 RoundRecord.toolCalls 来判断
                        if (currentContext.hasToolBeenCalled(toolName)) {
                            log.info("[LoopExecutor] [Round-{}] 工具 {} 已在本轮对话的历史中调用过，跳过重复调用，直接进入 deepThink (复用历史结果)",
                                currentContext.currentRound(), toolName);
                            // 跳过工具执行，直接进入 deepThink（复用历史中的工具结果）
                        } else {
                            // 首次调用此工具，记录并执行
                            log.info("[LoopExecutor] 决策调用工具: {} with args: {}", toolName, toolArgs);
                            sink.next("__TOOL_CALL__:" + toolName + "\n");

                            // A1. 执行工具
                            long toolStartTime = System.currentTimeMillis();
                            ToolExecutionResult toolResult = executeTool(toolName, toolArgs, currentContext);
                            long toolDuration = System.currentTimeMillis() - toolStartTime;

                            // 创建 ToolCall 记录
                            String argsJson = quickResult.toolArgsJson() != null ? quickResult.toolArgsJson() : "{}";
                            ToolCall toolCall = ToolCall.builder()
                                    .toolName(toolName)
                                    .parameters(argsJson)
                                    .build();
                            currentRoundToolCalls.add(toolCall);

                            // 创建 ToolResult 记录
                            ToolResult tr = ToolResult.builder()
                                    .toolName(toolName)
                                    .success(toolResult.isSuccess())
                                    .result(toolResult.getOutput())
                                    .errorMessage(toolResult.getErrorMessage())
                                    .durationMs(toolDuration)
                                    .callId(toolCall.callId())
                                    .build();
                            currentRoundToolResults.add(tr);

                            // A1.5. 【关键修复】提取 RAG 文档并更新 LoopContext
                            // 问题：之前工具结果只放入 chatHistory，但 Judge 从 LoopContext.ragDocuments 获取文档
                            // 修复：从工具 payload 中提取文档，更新到 currentContext
                            if (toolResult.isSuccess() && toolResult.getPayload() instanceof PowerKnowledgeResult pkr) {
                                List<Document> ragDocs = convertPowerKnowledgeToDocuments(pkr, toolName);
                                currentContext = currentContext.withRagDocuments(ragDocs);
                                log.info("[LoopExecutor] [Round-{}] RAG 文档已更新到 context: 数量={}, 来源工具={}",
                                    currentContext.currentRound(), ragDocs.size(), toolName);
                            }

                            // A2. 【关键】将"思考"和"工具结果"写入历史，成为上下文的一部分！
                            // 模拟: Assistant 想要调工具（记录思考过程）
                            chatHistory.add(new AssistantMessage(thoughtContent));

                            // 模拟: 工具返回了结果 (作为 User 角色输入)
                            String toolFeedback;
                            if (toolResult.isSuccess()) {
                                toolFeedback = String.format("【工具执行结果】工具 [%s] 执行成功:\n%s\n\n请根据以上结果回答用户问题。",
                                        toolName, toolResult.getOutput());
                            } else {
                                toolFeedback = String.format("【工具执行失败】工具 [%s] 执行失败:\n%s\n\n请分析错误原因并决定是否重试。",
                                        toolName, toolResult.getErrorMessage());
                            }
                            chatHistory.add(new UserMessage(toolFeedback));

                            log.info("[LoopExecutor] 工具结果已追加到历史，当前历史长度: {}", chatHistory.size());

                            // 工具执行完后，不立即 DeepThink，而是继续下一轮让 QuickThink 再次判断
                            // 这样可以支持"连续工具调用"场景
                            log.info("[LoopExecutor] [Round-{}] 工具执行完成，检查是否最后一轮: currentRound={}, maxRounds={}, isLastRound={}",
                                currentContext.currentRound(), currentContext.currentRound(), currentContext.maxRounds(), currentContext.isLastRound());
                            if (currentContext.isLastRound()) {
                                log.info("[LoopExecutor] 最后一轮，工具执行完直接生成回答");
                                // 最后一轮，工具执行完后需要生成回答
                                DeepThinkResult deepResult = deepThink(chatHistory, currentContext);
                                String finalAnswer = deepResult.getContent();
                                sink.next(finalAnswer);
                                break;
                            }
                            // 非最后一轮，继续循环
                            log.info("[LoopExecutor] [Round-{}] 非最后一轮，继续循环 (continue)...", currentContext.currentRound());
                            continue;
                        }
                        // 如果工具已调用过，执行会到这里，继续到下面的 deepThink
                    }

                    // ==================== 步骤 3: 深度思考 (Solver) ====================
                    // 无论刚才有没有调工具，现在 chatHistory 里已经包含了最新的状态
                    log.info("[LoopExecutor] [Round-{}] 开始调用 deepThink，chatHistory大小={}",
                        currentContext.currentRound(), chatHistory.size());
                    DeepThinkResult deepResult;
                    try {
                        deepResult = deepThink(chatHistory, currentContext);
                    } catch (Exception e) {
                        log.error("[LoopExecutor] [Round-{}] deepThink 异常!", currentContext.currentRound(), e);
                        deepResult = DeepThinkResult.builder()
                            .reasoningContent("")
                            .content("生成错误: " + e.getMessage())
                            .build();
                    }
                    String currentAnswer = deepResult.getContent();

                    // 记录最后一轮生成的答案（用于超时降级）
                    if (StringUtils.hasText(currentAnswer)) {
                        lastGeneratedAnswer.set(currentAnswer);
                    }

                    log.info("[LoopExecutor] [Round-{}] 深度思考完成: 回答长度={}, 回答预览={}",
                        currentContext.currentRound(),
                        currentAnswer != null ? currentAnswer.length() : 0,
                        currentAnswer != null && currentAnswer.length() > 0
                            ? currentAnswer.substring(0, Math.min(50, currentAnswer.length())) + "..."
                            : "(空)");

                    // ==================== 步骤 4: 反思评估 (Reflector) ====================
                    EvaluationResult evaluation = null;
                    boolean isLastRound = currentContext.isLastRound();

                    log.info("[LoopExecutor] [Round-{}] ========== 开始评判阶段 ==========", currentContext.currentRound());
                    log.info("[LoopExecutor] [Round-{}] 评判输入: 问题长度={}, 回答长度={}, RAG文档数={}",
                        currentContext.currentRound(),
                        currentContext.originalQuery() != null ? currentContext.originalQuery().length() : 0,
                        currentAnswer != null ? currentAnswer.length() : 0,
                        currentContext.ragDocuments() != null ? currentContext.ragDocuments().size() : 0);

                    if (!isLastRound) {
                        // 非最后一轮，执行评估
                        try {
                            evaluation = reflect(currentContext.originalQuery(), currentAnswer, currentContext);

                            // ========== 详细的评判结果日志 ==========
                            log.info("[LoopExecutor] [Round-{}] ========== 评判结果 ==========", currentContext.currentRound());
                            log.info("[LoopExecutor] [Round-{}] 评判结果: {}",
                                currentContext.currentRound(),
                                evaluation.isPassed() ? "✅ PASS (通过)" : "❌ FAIL (未通过)");
                            log.info("[LoopExecutor] [Round-{}] 原因: {}",
                                currentContext.currentRound(),
                                evaluation.getReason() != null ? evaluation.getReason() : "(无)");
                            if (evaluation.getSuggestion() != null && !evaluation.getSuggestion().isBlank()) {
                                log.info("[LoopExecutor] [Round-{}] 建议: {}",
                                    currentContext.currentRound(),
                                    evaluation.getSuggestion());
                            }
                            log.info("[LoopExecutor] [Round-{}] ======================================", currentContext.currentRound());

                        } catch (Exception e) {
                            log.error("[LoopExecutor] [Round-{}] 评判异常!", currentContext.currentRound(), e);
                            evaluation = EvaluationResult.pass("评估异常: " + e.getMessage());
                            log.info("[LoopExecutor] [Round-{}] 异常降级: 默认通过，原因: {}",
                                currentContext.currentRound(), e.getMessage());
                        }
                    } else {
                        log.info("[LoopExecutor] [Round-{}] 最后一轮，跳过评判 (强制输出)", currentContext.currentRound());
                    }

                    // ==================== 创建 RoundRecord 并更新 LoopContext ====================
                    long roundDuration = System.currentTimeMillis() - (Instant.now().toEpochMilli() - currentContext.getElapsedMs() + (currentContext.currentRound() > 0 ? 0 : currentContext.getElapsedMs()));
                    RoundRecord roundRecord = RoundRecord.builder()
                            .roundNumber(currentContext.currentRound())
                            .quickThought(currentRoundQuickThought)
                            .toolDecision(quickResult.decision())
                            .reasoningChain(quickResult.reasoningChain())
                            .toolCalls(new ArrayList<>(currentRoundToolCalls))
                            .toolResults(new ArrayList<>(currentRoundToolResults))
                            .generatedAnswer(currentAnswer)
                            .evaluation(evaluation)
                            .isFinalRound(isLastRound)
                            .durationMs(roundDuration)
                            .build();

                    // 更新 LoopContext，将 RoundRecord 添加到 roundHistory
                    currentContext = currentContext.withRoundRecord(roundRecord);
                    log.info("[LoopExecutor] [Round-{}] RoundRecord 已创建并添加到历史: hasToolCalls={}, evaluation={}",
                        currentContext.currentRound(), roundRecord.hasToolCalls(),
                        evaluation != null ? (evaluation.isPassed() ? "PASS" : "FAIL") : "无");

                    // 清空当前轮的累积数据，为下一轮准备
                    currentRoundToolCalls.clear();
                    currentRoundToolResults.clear();
                    currentRoundQuickThought = null;

                    // ==================== 步骤 5: 决策 ====================
                    boolean shouldContinue = (evaluation != null && !evaluation.isPassed());

                    log.info("[LoopExecutor] [Round-{}] ========== 决策阶段 ==========", currentContext.currentRound());
                    log.info("[LoopExecutor] [Round-{}] 决策: shouldContinue={}, isLastRound={}, 最终答案长度={}",
                        currentContext.currentRound(), shouldContinue, isLastRound,
                        currentAnswer != null ? currentAnswer.length() : 0);

                    if (shouldContinue && !isLastRound) {
                        // --- 失败：将"回答"和"批评"写入历史，驱动下一轮自我修正 ---
                        log.info("[LoopExecutor] [Round-{}] ❌ 评判未通过，准备进入下一轮自我修正", currentContext.currentRound());
                        log.info("[LoopExecutor] [Round-{}] 失败原因将作为反馈喂给下一轮...", currentContext.currentRound());

                        // A. 将 Assistant 的回答追加到历史
                        chatHistory.add(new AssistantMessage(currentAnswer));

                        // B. 将批评作为 User 的反馈追加到历史
                        String critique = String.format(
                                "【自我反思】你的回答未通过评估。\n" +
                                "原因：%s\n" +
                                "建议：%s\n\n" +
                                "请根据以上反馈重新思考并回答。如果之前的工具调用有问题，可以重新调用工具。",
                                evaluation.getReason(),
                                evaluation.getSuggestion() != null ? evaluation.getSuggestion() : "无"
                        );
                        chatHistory.add(new UserMessage(critique));

                        log.info("[LoopExecutor] [Round-{}] 批评已追加到历史，当前历史长度: {}",
                            currentContext.currentRound(), chatHistory.size());

                        // 继续下一轮
                        log.info("[LoopExecutor] [Round-{}] 继续下一轮 (continue)...", currentContext.currentRound());
                        continue;

                    } else {
                        // --- 成功或最后一轮：输出最终结果 ---
                        String terminationReason = shouldContinue
                            ? "已达到最大轮数 (最后一轮强制输出)"
                            : "✅ 评判通过，可以提前结束";
                        log.info("[LoopExecutor] [Round-{}] {}，输出最终答案", currentContext.currentRound(), terminationReason);

                        // 输出最终回答
                        if (StringUtils.hasText(currentAnswer)) {
                            log.info("[LoopExecutor] [Round-{}] 发送最终回答到前端，长度: {}",
                                currentContext.currentRound(), currentAnswer.length());
                            sink.next(currentAnswer);
                        } else {
                            log.warn("[LoopExecutor] [Round-{}] 最终回答为空，不发送!", currentContext.currentRound());
                        }

                        // 保存到记忆
                        saveToMemory(context.getSessionId(), currentAnswer, thoughtContent);
                        log.info("[LoopExecutor] [Round-{}] 已保存到记忆，准备退出循环", currentContext.currentRound());
                        break;
                    }
                }

                log.info("[LoopExecutor] ===== 循环正常结束，总共迭代了 {} 次 =====", loopIteration);
                sink.complete();

            } catch (Exception e) {
                log.error("[LoopExecutor] 执行异常", e);
                sink.error(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    // ==================== 单轮执行 ====================

    /**
     * 执行单轮循环（支持复用上一轮的工具结果）
     * <p>用于多轮循环场景，第二轮及后续轮次复用第一轮的工具执行结果（如RAG）</p>
     * <h3>多轮消息构造：</h3>
     * <pre>
     * 第一轮：
     *   [System: 系统提示 + 工具描述]
     *   [User: 原始问题]
     *   → 快速思考 → 工具执行 → 深度思考 → 反思评估
     *
     * 第二轮（如果评估不通过）：
     *   [System: 系统提示 + 工具描述]
     *   [User: 原始问题]
     *   [Assistant: 第一轮的回答]
     *   [User: 工具执行结果: {复用第一轮的RAG结果}]
     *   [User: 自我评估反馈: 原因: xxx 建议: xxx]
     *   → 快速思考（不调工具）→ 深度思考 → 反思评估
     * </pre>
     *
     * @param context          循环上下文
     * @param securityContext  安全上下文
     * @param reusedToolOutput 复用的工具输出（第一轮的RAG结果）
     * @param reusedQuickThought 复用的快速思考内容
     * @return 轮次记录
     * @deprecated 已被新的"一切皆消息"架构取代，见 {@link #executeStream(AgentContext)}
     */
    @Deprecated
    private RoundRecord executeSingleRoundWithReuse(
            LoopContext context,
            SecurityContext securityContext,
            String reusedToolOutput,
            String reusedQuickThought) {

        long roundStartTime = Instant.now().toEpochMilli();
        int roundNumber = context.currentRound();

        RoundRecord.RoundRecordBuilder builder = RoundRecord.builder()
                .roundNumber(roundNumber)
                .timestamp(roundStartTime);

        try {
            SecurityContextHolder.setContext(securityContext);

            // 构建消息列表（多轮上下文）
            List<Message> messages = buildMessagesForMultiRound(context, reusedToolOutput, reusedQuickThought);

            // 1. 快速思考 - 第二轮通常不需要再调工具
            QuickThinkResult thinkResult;
            if (context.isFirstRound() || reusedToolOutput == null) {
                // 第一轮或没有复用结果，正常执行快速思考
                thinkResult = quickThink(messages, context);
            } else {
                // 后续轮次：直接复用第一轮的快速思考结果，不调工具
                thinkResult = new QuickThinkResult(
                        reusedQuickThought != null ? reusedQuickThought : "复用第一轮思考",
                        "DIRECT_ANSWER",
                        false,  // 不需要调工具
                        null,
                        new HashMap<>(),
                        null,
                        List.of("复用第一轮工具结果")
                );
            }

            builder.quickThought(thinkResult.thoughtContent())
                    .toolDecision(thinkResult.decision())
                    .reasoningChain(thinkResult.reasoningChain());

            // 2. 工具执行（仅第一轮需要）
            String toolOutput = null;
            if (context.isFirstRound() && thinkResult.needsToolCall()) {
                ToolExecutionResult execResult = executeTool(
                        thinkResult.toolName(),
                        thinkResult.toolArgs(),
                        context
                );

                List<ToolCall> toolCalls = List.of(ToolCall.of(thinkResult.toolName(), thinkResult.toolArgsJson()));
                List<ToolResult> toolResults = List.of(new ToolResult(
                        thinkResult.toolName(),
                        execResult.isSuccess(),
                        execResult.getOutput(),
                        execResult.getErrorMessage(),
                        0L,
                        toolCalls.get(0).callId()
                ));

                builder.toolCalls(toolCalls)
                        .toolResults(toolResults);

                toolOutput = execResult.getOutput();
                messages = appendToolResult(messages, thinkResult.thoughtContent(), toolOutput, context);
            } else if (reusedToolOutput != null && !context.isFirstRound()) {
                // 后续轮次：复用第一轮的工具结果，记录但不执行
                RoundRecord lastRound = context.getLastRound();
                if (lastRound != null && lastRound.toolCalls() != null) {
                    builder.toolCalls(lastRound.toolCalls())
                            .toolResults(lastRound.toolResults());
                }
                toolOutput = reusedToolOutput;
            }

            // 3. 深度思考 - 生成最终回答
            DeepThinkResult deepResult = deepThink(messages, context);
            builder.generatedAnswer(deepResult.getContent())
                    .deepThinkingContent(deepResult.getReasoningContent());

            // 4. 反思评估（非最后一轮）
            EvaluationResult evaluation = null;
            if (!context.isLastRound()) {
                evaluation = reflect(context.originalQuery(),
                        deepResult.getContent(), context);
            }

            builder.evaluation(evaluation)
                    .isFinalRound(context.isLastRound())
                    .durationMs(Instant.now().toEpochMilli() - roundStartTime);

            RoundRecord record = builder.build();
            log.info("[LoopExecutor] Round {}: {}", roundNumber, record.getSummary());
            return record;

        } catch (Exception e) {
            log.error("[LoopExecutor] Round {} error", roundNumber, e);
            builder.durationMs(Instant.now().toEpochMilli() - roundStartTime);
            builder.generatedAnswer("处理错误: " + e.getMessage());
            return builder.build();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ==================== 快速思考 ====================

    /**
     * 快速思考
     * <p>让 LLM 快速分析问题，判断是否需要调用工具</p>
     *
     * @param messages 消息列表（已包含预构建的工具描述）
     * @param context  循环上下文
     * @return 快速思考结果
     */
    private QuickThinkResult quickThink(List<Message> messages, LoopContext context) {
        try {
            List<Message> dashMessages = new ArrayList<>(messages);

            // 添加反馈消息（如果有）
            String feedback = context.buildFeedbackMessage();
            if (feedback != null) {
                log.info("[quickThink] 添加反馈消息: {}", feedback);
                dashMessages.add(new UserMessage(feedback));
            }

            // 转换为 DashScope 格式并调用
            List<com.alibaba.dashscope.common.Message> dashMsgs = convertToDashScopeMessages(dashMessages);
            log.info("[quickThink] 调用 LLM，消息数量: {}", dashMsgs.size());
            DeepThinkResult result = nativeService.deepThink(WORKER_MODEL, dashMsgs);
            String content = result.getContent();

            log.info("[quickThink] LLM返回内容长度: {}, 预览: {}",
                content != null ? content.length() : 0,
                content != null && content.length() > 0
                    ? content.substring(0, Math.min(100, content.length())) + "..."
                    : "(空)");

            // 尝试提取工具调用 JSON
            String jsonToolCall = extractJson(content);
            if (jsonToolCall != null) {
                log.info("[quickThink] 检测到工具调用 JSON: {}", jsonToolCall);
                Map<String, Object> toolMap = objectMapper.readValue(jsonToolCall, Map.class);
                String toolName = (String) toolMap.get("tool");
                Map<String, Object> args = toolMap.containsKey("args") ?
                        (Map<String, Object>) toolMap.get("args") : new HashMap<>();

                log.info("[quickThink] 解析工具调用: tool={}, args={}", toolName, args);
                return new QuickThinkResult(
                        content,
                        "CALL_TOOL:" + toolName,
                        true,
                        toolName,
                        args,
                        objectMapper.writeValueAsString(args),
                        List.of("需要工具: " + toolName)
                );
            }

            // 不需要工具，直接回答
            log.info("[quickThink] 未检测到工具调用，返回直接回答");
            return new QuickThinkResult(
                    content,
                    "DIRECT_ANSWER",
                    false,
                    null,
                    new HashMap<>(),
                    null,
                    List.of("直接回答")
            );

        } catch (Exception e) {
            log.error("[quickThink] 快速思考失败!", e);
            return new QuickThinkResult(
                    "思考错误",
                    "DIRECT_ANSWER",
                    false,
                    null,
                    new HashMap<>(),
                    null,
                    List.of("错误处理")
            );
        }
    }

    // ==================== 工具执行 ====================

    /**
     * 执行工具
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @param context  循环上下文
     * @return 工具执行结果
     */
    private ToolExecutionResult executeTool(String toolName, Map<String, Object> args, LoopContext context) {
        try {
            // 从 ToolRegistry O(1) 获取工具
            AgentTool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                log.warn("[LoopExecutor] Tool not found: {}", toolName);
                return ToolExecutionResult.failure("Tool not found: " + toolName);
            }

            log.info("[LoopExecutor] Executing tool: {}", toolName);
            return tool.execute(
                    ToolExecutionRequest.builder()
                            .toolName(toolName)
                            .arguments(args)
                            .build(),
                    context.toAgentContext()
            );

        } catch (Exception e) {
            log.error("[LoopExecutor] Tool error: {}", toolName, e);
            return ToolExecutionResult.failure("Tool error: " + e.getMessage());
        }
    }

    // ==================== 深度思考 ====================

    /**
     * 深度思考
     * <p>使用深度思考模型生成最终回答</p>
     *
     * @param messages 消息列表
     * @param context  循环上下文
     * @return 深度思考结果
     */
    private DeepThinkResult deepThink(List<org.springframework.ai.chat.messages.Message> messages, LoopContext context) {
        try {
            List<com.alibaba.dashscope.common.Message> dashMsgs = convertToDashScopeMessages(messages);
            return nativeService.deepThink(WORKER_MODEL, dashMsgs);
        } catch (Exception e) {
            log.error("[LoopExecutor] Deep think error", e);
            return DeepThinkResult.builder()
                    .reasoningContent("")
                    .content("生成错误: " + e.getMessage())
                    .build();
        }
    }

    // ==================== 反思评估 ====================

    /**
     * 反思评估
     * <p>评估生成的回答质量</p>
     *
     * @param query  用户问题
     * @param answer 生成的回答
     * @param context 循环上下文
     * @return 评估结果
     */
    private EvaluationResult reflect(String query, String answer, LoopContext context) {
        try {
            Map<String, Object> ragContext = new HashMap<>();
            if (context.ragSummary() != null) {
                ragContext.put("rag_summary", context.ragSummary());
            }
            if (context.ragDocuments() != null && !context.ragDocuments().isEmpty()) {
                ragContext.put("documents", context.ragDocuments());
            }

            return reflectionService.evaluate(query, answer, ragContext);

        } catch (Exception e) {
            log.error("[LoopExecutor] Reflection error", e);
            return EvaluationResult.pass("Reflection error: " + e.getMessage());
        }
    }

    /**
     * 将 PowerKnowledgeResult 转换为 Spring AI Document 列表
     * <p>用于 Judge 评估时获取 RAG 检索到的文档内容</p>
     *
     * @param result    知识库检索结果
     * @param toolName  工具名称
     * @return Document 列表
     */
    private List<Document> convertPowerKnowledgeToDocuments(PowerKnowledgeResult result, String toolName) {
        List<Document> documents = new ArrayList<>();

        // 1. 从 rawContentSnippets 创建文档（核心内容）
        if (result.rawContentSnippets() != null) {
            for (int i = 0; i < result.rawContentSnippets().size(); i++) {
                String snippet = result.rawContentSnippets().get(i);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", toolName);
                metadata.put("index", i);
                metadata.put("type", "snippet");
                documents.add(new Document(snippet, metadata));
            }
        }

        // 2. 从 sources 创建文档（来源信息）
        if (result.sources() != null) {
            for (int i = 0; i < result.sources().size(); i++) {
                PowerKnowledgeResult.SourceDocument source = result.sources().get(i);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", toolName);
                metadata.put("index", i);
                metadata.put("type", "source");
                metadata.put("filename", source.filename());
                metadata.put("downloadUrl", source.downloadUrl());
                // 添加文本内容以便 Judge 查看来源文件信息
                String content = String.format("[来源文件] 文件名: %s", source.filename());
                documents.add(new Document(content, metadata));
            }
        }

        log.debug("[LoopExecutor] 转换 RAG 文档: snippets={}, sources={}, 总计={}",
            result.rawContentSnippets() != null ? result.rawContentSnippets().size() : 0,
            result.sources() != null ? result.sources().size() : 0,
            documents.size());

        return documents;
    }

    // ==================== 循环控制 ====================

    /**
     * 判断是否应该继续循环
     * <p>评估通过或最后一轮时停止循环</p>
     *
     * @param context 循环上下文
     * @return true 如果应该继续
     * @deprecated 已被新的"一切皆消息"架构取代，见 {@link #executeStream(AgentContext)}
     */
    @Deprecated
    private boolean shouldContinueLoop(LoopContext context) {
        RoundRecord lastRound = context.getLastRound();
        if (lastRound == null) {
            return true;
        }

        // 最后一轮不继续
        if (lastRound.isFinalRound()) {
            return false;
        }

        // 评估通过则不继续
        if (lastRound.passedEvaluation()) {
            return false;
        }

        return true;
    }

    // ==================== 消息构建 ====================

    /**
     * 构建消息列表
     * <p>包含系统提示词、历史记录、上下文和用户问题</p>
     *
     * @param context 循环上下文
     * @return 消息列表
     */
    private List<org.springframework.ai.chat.messages.Message> buildMessages(LoopContext context) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        // 系统提示词
        String systemPrompt = buildSystemPrompt(context);
        messages.add(new SystemMessage(systemPrompt));

        // 历史记录
        List<org.springframework.ai.chat.messages.Message> history = loadRecentHistory(context.sessionId(), 5);
        messages.addAll(history);

        // 上下文提示（RAG、历史摘要、反馈）
        String contextPrompt = context.buildContextPrompt();
        if (!contextPrompt.isBlank()) {
            messages.add(new UserMessage(contextPrompt));
        }

        // 用户原始问题
        messages.add(new UserMessage(context.originalQuery()));

        return messages;
    }

    /**
     * 构建多轮循环的消息列表
     * <p>第二轮及后续轮次包含上一轮的回答和反思反馈</p>
     *
     * <h3>消息结构：</h3>
     * <pre>
     * 第一轮：
     *   [System: 系统提示 + 工具描述]
     *   [User: 原始问题]
     *
     * 第二轮（评估不通过）：
     *   [System: 系统提示 + 工具描述]
     *   [User: 原始问题]
     *   [Assistant: 第一轮的回答]
     *   [User: 工具执行结果: {RAG结果}]
     *   [User: 自我评估反馈: 原因: xxx 建议: xxx，请重新回答]
     * </pre>
     *
     * @param context          循环上下文
     * @param reusedToolOutput 复用的工具输出（第一轮的RAG结果）
     * @param reusedQuickThought 复用的快速思考内容
     * @return 消息列表
     * @deprecated 已被新的"一切皆消息"架构取代，见 {@link #executeStream(AgentContext)}
     */
    @Deprecated
    private List<org.springframework.ai.chat.messages.Message> buildMessagesForMultiRound(
            LoopContext context,
            String reusedToolOutput,
            String reusedQuickThought) {

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        // 系统提示词
        String systemPrompt = buildSystemPrompt(context);
        messages.add(new SystemMessage(systemPrompt));

        // 历史记录
        List<org.springframework.ai.chat.messages.Message> history = loadRecentHistory(context.sessionId(), 5);
        messages.addAll(history);

        // 用户原始问题
        messages.add(new UserMessage(context.originalQuery()));

        // 如果是第二轮及以后，添加上一轮的上下文
        if (!context.isFirstRound()) {
            RoundRecord lastRound = context.getLastRound();

            // 1. 添加上一轮的快速思考（作为Assistant消息）
            if (lastRound != null && StringUtils.hasText(lastRound.quickThought())) {
                messages.add(new AssistantMessage(lastRound.quickThought()));
            }

            // 2. 添加工具执行结果（复用第一轮的RAG结果）
            if (StringUtils.hasText(reusedToolOutput)) {
                String toolResultMsg = String.format("工具执行结果:\n%s\n\n请根据以上检索结果回答用户问题。", reusedToolOutput);
                messages.add(new UserMessage(toolResultMsg));
            }

            // 3. 添加反思反馈（核心！）
            String feedback = buildFeedbackForNextRound(context);
            if (StringUtils.hasText(feedback)) {
                messages.add(new UserMessage(feedback));
            }
        }

        return messages;
    }

    /**
     * 构建系统提示词
     * <p>从数据库加载 SYSTEM-RAG-v1.0 模板并渲染</p>
     *
     * @param context 循环上下文
     * @return 渲染后的系统提示词
     */
    private String buildSystemPrompt(LoopContext context) {
        try {
            // 获取工具描述（包含所有可用工具：knowledge_search, web_search, etc.）
            String toolsDesc = toolRegistry.getFullToolsDescription();

            // 准备模板变量
            Map<String, Object> promptVars = new HashMap<>();
            promptVars.put("tools_desc", toolsDesc);

            // 渲染模板
            String systemPromptText = sysPromptService.render(SYSTEM_PROMPT_CODE, promptVars);

            if (systemPromptText != null && !systemPromptText.isEmpty()) {
                return systemPromptText;
            }

            // 降级方案
            return "You are a helpful assistant. Tools available:\n" + toolsDesc;

        } catch (Exception e) {
            log.warn("[LoopExecutor] 构建系统提示词失败", e);
            return "You are a helpful assistant.";
        }
    }

    /**
     * 追加工具执行结果到消息列表
     *
     * @param originalMessages 原始消息列表
     * @param thought          思考内容
     * @param toolResult       工具执行结果
     * @param context          循环上下文
     * @return 新的消息列表
     */
    private List<org.springframework.ai.chat.messages.Message> appendToolResult(List<org.springframework.ai.chat.messages.Message> originalMessages, String thought,
                                                                          String toolResult, LoopContext context) {
        List<org.springframework.ai.chat.messages.Message> newMessages = new ArrayList<>(originalMessages);
        newMessages.add(new AssistantMessage(thought));

        String resultMessage = String.format("工具执行结果: %s\n\n请根据结果回答用户问题。", toolResult);
        newMessages.add(new UserMessage(resultMessage));

        return newMessages;
    }

    /**
     * 构建下一轮的反思反馈消息
     * <p>当上一轮评估失败时，构建反馈传递给下一轮</p>
     *
     * <h3>反馈格式：</h3>
     * <pre>
     * 【自我评估反馈】
     * 你之前的回答存在以下问题：
     * 原因: {评估失败的原因}
     * 建议: {改进建议}
     *
     * 请根据以上反馈重新回答用户问题。
     * </pre>
     *
     * @param context 循环上下文
     * @return 反馈消息字符串，如果不需要反馈返回 null
     */
    private String buildFeedbackForNextRound(LoopContext context) {
        RoundRecord lastRound = context.getLastRound();
        if (lastRound == null) {
            return null;
        }

        EvaluationResult evaluation = lastRound.evaluation();
        // 关键修复：只有评估失败才需要反馈
        if (evaluation == null || evaluation.isPassed()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【自我评估反馈】\n");
        sb.append("你之前的回答存在以下问题：\n");

        if (evaluation.getReason() != null && !evaluation.getReason().isBlank()) {
            sb.append("原因: ").append(evaluation.getReason()).append("\n");
        }

        if (evaluation.getSuggestion() != null && !evaluation.getSuggestion().isBlank()) {
            sb.append("建议: ").append(evaluation.getSuggestion()).append("\n");
        }

        sb.append("\n请根据以上反馈重新回答用户问题，确保改进之前的问题。");

        return sb.toString();
    }

    /**
     * 加载最近的历史消息
     *
     * @param sessionId 会话 ID
     * @param n         加载条数
     * @return 历史消息列表
     */
    private List<org.springframework.ai.chat.messages.Message> loadRecentHistory(String sessionId, int n) {
        List<org.springframework.ai.chat.messages.Message> allMessages = chatMemoryRepository.findByConversationId(sessionId);
        if (allMessages == null) return new ArrayList<>();
        if (allMessages.size() <= n) return allMessages;
        return allMessages.subList(allMessages.size() - n, allMessages.size());
    }

    // ==================== 辅助方法 ====================

    /**
     * 从文本中提取 JSON
     * <p>支持多种格式：markdown 代码块、纯 JSON</p>
     *
     * @param text 输入文本
     * @return 提取的 JSON 字符串，如果未找到返回 null
     */
    private String extractJson(String text) {
        if (text == null) return null;
        try {
            // 尝试提取 ```json ... ``` 格式
            Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
            // 尝试纯 JSON
            if (text.trim().startsWith("{") && text.trim().endsWith("}")) {
                return text;
            }
            // 尝试查找 {"tool": ...} 片段
            int start = text.indexOf("{\"tool\":");
            if (start >= 0) {
                int end = text.lastIndexOf("}");
                if (end > start) {
                    return text.substring(start, end + 1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 转换 Spring AI 消息为 DashScope 消息
     *
     * @param springMessages Spring AI 消息列表
     * @return DashScope 消息列表
     */
    private List<com.alibaba.dashscope.common.Message> convertToDashScopeMessages(List<org.springframework.ai.chat.messages.Message> springMessages) {
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

    // ==================== 结果处理 ====================

    /**
     * 保存单轮结果到聊天记忆
     * <p>用于流式循环场景，直接保存 RoundRecord</p>
     *
     * @param sessionId 会话 ID
     * @param round     轮次记录
     */
    /**
     * 保存到记忆（旧版，兼容 RoundRecord）
     */
    private void saveToMemory(String sessionId, RoundRecord round) {
        try {
            List<org.springframework.ai.chat.messages.Message> current = chatMemoryRepository.findByConversationId(sessionId);
            if (current == null) {
                current = new ArrayList<>();
            }

            // 组合完整回答（思考 + 回答）
            String fullAnswer = "";
            String thinking = round.getThinkingProcess();
            if (StringUtils.hasText(thinking)) {
                fullAnswer += "```\n" + thinking + "\n```\n\n";
            }
            fullAnswer += round.generatedAnswer() != null ? round.generatedAnswer() : "";

            current.add(new AssistantMessage(fullAnswer));
            chatMemoryRepository.saveAll(sessionId, current);

        } catch (Exception e) {
            log.error("[LoopExecutor] Failed to save memory", e);
        }
    }

    /**
     * 保存到记忆（新版，直接保存回答和思考）
     *
     * @param sessionId 会话 ID
     * @param answer    最终回答
     * @param thought   思考过程（可选）
     */
    private void saveToMemory(String sessionId, String answer, String thought) {
        try {
            List<org.springframework.ai.chat.messages.Message> current = chatMemoryRepository.findByConversationId(sessionId);
            if (current == null) {
                current = new ArrayList<>();
            }

            // 组合完整回答（思考 + 回答）
            String fullAnswer = "";
            if (StringUtils.hasText(thought)) {
                fullAnswer += "```\n" + thought + "\n```\n\n";
            }
            fullAnswer += answer != null ? answer : "";

            current.add(new AssistantMessage(fullAnswer));
            chatMemoryRepository.saveAll(sessionId, current);

        } catch (Exception e) {
            log.error("[LoopExecutor] Failed to save memory", e);
        }
    }

    // ==================== 内部记录 ====================

    /**
     * 快速思考结果
     * <p>封装快速思考阶段的输出</p>
     */
    private record QuickThinkResult(
            /** 思考内容 */
            String thoughtContent,
            /** 决策结果 */
            String decision,
            /** 是否需要工具调用 */
            boolean needsToolCall,
            /** 工具名称 */
            String toolName,
            /** 工具参数 */
            Map<String, Object> toolArgs,
            /** 工具参数 JSON 字符串 */
            String toolArgsJson,
            /** 推理链条 */
            List<String> reasoningChain
    ) {}
}
