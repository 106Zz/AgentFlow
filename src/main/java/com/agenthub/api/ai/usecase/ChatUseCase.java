package com.agenthub.api.ai.usecase;

import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeTool;
import com.agenthub.api.knowledge.domain.vo.StreamChunk;
import com.agenthub.api.prompt.builder.CaseSnapshotBuilder;
import com.agenthub.api.prompt.context.PromptContextHolder;
import com.agenthub.api.prompt.service.ICaseSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天用例 (ChatUseCase)
 * 职责: 处理通用对话 (CHAT) 意图，集成 RAG、记忆、角色扮演等高级能力。
 * 它是 "Smart" Client 的主场。
 */
@Deprecated
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUseCase implements AIUseCase {

    private final PowerKnowledgeTool knowledgeTool;
    private final ChatModel chatModel;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ObjectMapper objectMapper;
    private final ICaseSnapshotService caseSnapshotService;

    /**
     * RAG 系统提示词
     * 优先从数据库获取，降级时使用硬编码默认值
     */
    private static final String DEFAULT_RAG_SYSTEM_PROMPT = """
            # 角色定位
            你是一位面向企业客户的专业知识库分析师，能够基于知识库数据提供高质量、专业级的解答。你的回答需要详细、准确、有深度。

            # 思考与行动准则（关键！）
            在回答任何问题前，请按以下步骤思考：
            1. **判断意图**：用户是在闲聊（"你好"）还是在问业务（"现货怎么结算"）？
               - 闲聊 -> 直接热情回复，不调用工具。
               - 业务 -> **必须**调用 `powerKnowledge` 工具。
            2. **提取关键词**：调用工具时，请将用户口语化的问题转化为精准的检索词（例如将 "今年电价咋算的" 转为 "2026年 现货电价 结算公式"）。
            3. **依赖事实**：如果工具返回结果为空，你必须回答"知识库中未找到相关内容"，**严禁**利用你的通用知识瞎编。

            # 核心要求

            1. 内容要求（最重要）
               - 提供详细、完整的分析
               - 包含所有相关数据和信息
               - 进行必要的推理和计算
               - 给出明确的结论

            2. 格式要求
               - 使用清晰的 Markdown 格式
               - 合理使用标题、列表、粗体来增强可读性
               - 用 [1] [2] 标记引用来源

            # 🔴 重要：关于引用来源

            下方的 {context} 中已经包含了：
            - 参考文档列表（带编号的文件名）
            - 参考文档内容片段

            **你必须遵守以下规则**：
            1. **只引用** {context} 中列出的文档，**严禁编造**文档名称
            2. 回答时使用 [1] [2] [3] 这样的编号来引用 {context} 中的文档

            # 输出结构

            ## 📄 参考文档
            （从 {context} 中提取参考文档名称，使用 [1] [2] [3] 编号，严禁编造）

            ## 一、问题分析
            （简要总结用户的问题）

            ## 二、详细解答
            （基于 {context} 中的文档内容进行回答，用 [1] [2] 标注来源）

            ## 三、结论
            （给出明确的结论）

            # 重要提示

            内容的完整性和准确性至关重要。如果 {context} 中的文档片段为空或未找到相关内容，请直接回答：
            > 抱歉，当前知识库暂未收录该内容。

            现在开始回答用户问题。
            """;

    @Override
    public boolean support(String intent) {
        return "CHAT".equals(intent) || intent == null || intent.isEmpty();
    }

    @Override
    public AIResponse execute(AIRequest request) {
        log.info(">>>> [UseCase] 进入通用 RAG 问答模式 (Stream + Reasoning): {}", request.query());

        long startTime = System.currentTimeMillis();
        String conversationId = request.sessionId();

        // 这里显式使用了 this.chatMemoryRepository 构建 Advisor
        ChatClient smartClient = buildSmartClient(conversationId);

        PowerKnowledgeQuery searchArgs = new PowerKnowledgeQuery(request.query(), 3, null, null);
        PowerKnowledgeResult searchResult = knowledgeTool.retrieve(searchArgs);

        // 🔧 修复：将实际的文档内容片段拼接为完整的上下文，而不是只用摘要
        String context = buildRagContext(searchResult);

        // 用于收集完整响应
        AtomicReference<String> fullResponse = new AtomicReference<>("");
        AtomicReference<String> reasoningContent = new AtomicReference<>(null);

        // 改为获取完整 ChatResponse 以提取思考过程
        Flux<String> stream = smartClient.prompt()
                .system(s -> s.param("context", context))
                .user(request.query())
                .stream()
                .chatResponse()
                .map(resp -> {
                    // 1. 提取思考过程 (DeepSeek R1 等)
                    if (reasoningContent.get() == null && resp.getResult() != null
                            && resp.getResult().getOutput() != null) {
                        Map<String, Object> meta = resp.getResult().getOutput().getMetadata();
                        if (meta != null) {
                            Object t = meta.get("reasoning_content");
                            if (t == null) t = meta.get("reasoning");
                            if (t != null) reasoningContent.set(t.toString());
                        }
                    }

                    // 2. 提取正文（不重新赋值 content 变量，避免 lambda effectively final 问题）
                    final String content;
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        content = resp.getResult().getOutput().getText();
                        // 累积完整响应
                        fullResponse.updateAndGet(existing -> existing + content);
                    } else {
                        content = "";
                    }

                    // 3. 包装为 StreamChunk 并转为 JSON 字符串
                    try {
                        return objectMapper.writeValueAsString(
                                new StreamChunk(reasoningContent.get(), content, conversationId));
                    } catch (Exception e) {
                        log.warn("JSON序列化失败", e);
                        return "{}";
                    }
                })
                .doOnComplete(() -> {
                    // 流式结束后冻结 Case
                    freezeCase(request, searchResult, fullResponse.get(),
                            reasoningContent.get(), startTime, context);
                })
                .doOnError(error -> {
                    // 发生错误时记录失败的 Case
                    freezeErrorCase(request, searchResult, error, startTime);
                });

        return AIResponse.ofStream(stream);
    }

    /**
     * 冻结成功的 Case
     */
    private void freezeCase(AIRequest request, PowerKnowledgeResult searchResult,
                           String fullResponse, String reasoning, long startTime, String ragContext) {
        try {
            long duration = System.currentTimeMillis() - startTime;

            // 构建 RAG 文档节点
            ObjectNode documentsNode = objectMapper.createObjectNode();
            if (searchResult.sources() != null) {
                for (int i = 0; i < searchResult.sources().size(); i++) {
                    PowerKnowledgeResult.SourceDocument doc = searchResult.sources().get(i);
                    ObjectNode docNode = documentsNode.putObject(String.valueOf(i + 1));
                    docNode.put("filename", doc.filename());
                    docNode.put("download_url", doc.downloadUrl());
                }
            }

            // 构建 Token 使用情况（估算）
            ObjectNode tokenUsage = objectMapper.createObjectNode();
            tokenUsage.put("input_chars", request.query().length() + ragContext.length());
            tokenUsage.put("output_chars", fullResponse.length());

            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("CHAT")
                            .intent("CHAT.RAG_QUERY")
                            .input(request.query(), request.userId() != null ? Long.valueOf(request.userId()) : null, request.sessionId())
                            .capturePromptData()
                            .ragContext(request.query(), documentsNode,
                                    searchResult.sources() != null ? searchResult.sources().size() : 0)
                            .outputData(fullResponse, duration, tokenUsage)
                            .modelData("dashscope", "deepseek-v3.2", null)
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );

        } catch (Exception e) {
            log.warn("[ChatUseCase] Case 冻结失败: {}", e.getMessage());
        }
    }

    /**
     * 冻结失败的 Case
     */
    private void freezeErrorCase(AIRequest request, PowerKnowledgeResult searchResult,
                                 Throwable error, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;

            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("CHAT")
                            .intent("CHAT.RAG_QUERY")
                            .input(request.query(), request.userId() != null ? Long.valueOf(request.userId()) : null, request.sessionId())
                            .capturePromptData()
                            .status("FAILED")
                            .errorMessage(error.getMessage())
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );

        } catch (Exception e) {
            log.warn("[ChatUseCase] Error Case 冻结失败: {}", e.getMessage());
        }
    }

    /**
     * 构建完整的 RAG 上下文
     * 将检索到的文档片段和来源信息拼接为结构化的上下文字符串
     *
     * @param searchResult 检索结果
     * @return 格式化的上下文字符串
     */
    private String buildRagContext(PowerKnowledgeResult searchResult) {
        StringBuilder context = new StringBuilder();

        // 1. 添加来源文件列表
        if (searchResult.sources() != null && !searchResult.sources().isEmpty()) {
            context.append("📄 参考文档：\n");
            for (int i = 0; i < searchResult.sources().size(); i++) {
                PowerKnowledgeResult.SourceDocument source = searchResult.sources().get(i);
                context.append(String.format("[%d] %s\n", i + 1, source.filename()));
            }
            context.append("\n");
        }

        // 2. 添加文档内容片段（这是关键！）
        if (searchResult.rawContentSnippets() != null && !searchResult.rawContentSnippets().isEmpty()) {
            context.append("📄 参考文档内容：\n\n");
            for (int i = 0; i < searchResult.rawContentSnippets().size(); i++) {
                String snippet = searchResult.rawContentSnippets().get(i);
                context.append(String.format("--- 片段 %d ---\n%s\n\n", i + 1, snippet));
            }
        } else {
            context.append("（知识库中未找到相关内容）\n");
        }

        log.info("🔧 [RAG Context] 长度: {} 字符, 片段数: {}",
                context.length(),
                searchResult.rawContentSnippets() != null ? searchResult.rawContentSnippets().size() : 0);

        return context.toString();
    }

    private ChatClient buildSmartClient(String conversationId) {
        // 从数据库获取 RAG 系统提示词，如果未配置则使用默认值
        String systemPrompt = PromptContextHolder.getSystem("SYSTEM-RAG-v1.0");
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            systemPrompt = DEFAULT_RAG_SYSTEM_PROMPT;
            log.debug("[ChatUseCase] 使用默认硬编码 RAG 提示词（数据库未配置 SYSTEM-RAG-v1.0 ）");
        }

        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        // 在这里显式注入了 chatMemoryRepository
                        MessageChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .chatMemoryRepository(chatMemoryRepository) // <--- 就是这里！
                                        .maxMessages(20)
                                        .build()
                        )
                        .conversationId(conversationId)
                        .build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }
}
