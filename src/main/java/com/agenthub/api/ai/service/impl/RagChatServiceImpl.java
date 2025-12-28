package com.agenthub.api.ai.service.impl;

import com.agenthub.api.common.utils.SecurityUtils;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Or;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 聊天服务实现
 * 使用 LangChain4j 官方 RAG API
 * 
 * 功能：
 * - 用户权限过滤（管理员/普通用户）
 * - Redis 聊天记忆（官方 RedisChatMemoryStore）
 * - Reranker 重排序（gte-rerank-v2）
 * - Flux 流式输出
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagChatServiceImpl {

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ScoringModel scoringModel;
    private final ChatMemoryProvider chatMemoryProvider;  // 官方 Redis 聊天记忆

    // RAG 配置
    private static final int RECALL_TOP_K = 50;  // 召回数量
    private static final double MIN_SCORE = 0.3; // Rerank 最小分数

    private static final String SYSTEM_PROMPT = """
        # 角色定位
        你是一位面向企业客户的专业知识库分析师，能够基于知识库数据提供高质量、专业级的解答。你的回答需要详细、准确、有深度。
        
        # 核心要求
        
        1. 内容要求（最重要）
           - 提供详细、完整的分析
           - 包含所有相关数据和信息
           - 进行必要的推理和计算
           - 给出明确的结论
        
        2. 格式要求（次要）
           - 使用自然段落，不使用 Markdown 格式符号
           - 用空行分隔段落
           - 用 [1] [2] 标记引用来源
        
        # 输出结构
        
        第一部分：参考文档
        📄 参考文档：
        [1] 完整文件名1.pdf
        [2] 完整文件名2.pdf
        
        第二部分：详细回答
        用自然段落提供完整、详细的回答。包括：
        - 直接回答用户问题
        - 提供相关的数据和事实
        - 进行必要的分析和推理
        - 说明数据来源和依据
        - 给出明确的结论
        
        # 格式规范
        
        允许使用：
        - 自然段落（用空行分隔）
        - 引用标记：[1] [2]
        - 表情符号：📄 🌐
        
        不要使用：
        - Markdown 标题（### 等）
        - 粗体斜体（** * 等）
        - 项目符号（- * 等）
        - 数字列表（1. 2. 等）
        
        # 重要提示
        
        内容的完整性和准确性比格式更重要。请确保提供详细、有深度的回答，同时尽量使用自然段落而不是 Markdown 格式。
        
        如果知识库完全没有相关信息，在最终答案中写：抱歉，当前知识库暂未收录该内容。
        
        现在开始回答用户问题。
        """;

    /**
     * 同步聊天
     * 
     * @param sessionId 会话ID（用作 Redis 的 memoryId）
     * @param question 用户问题
     * @return AI 回答
     */
    public String chat(String sessionId, String question) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        log.info("【RAG Chat】会话ID: {}, 用户ID: {}, 管理员: {}, 问题: {}", 
                 sessionId, userId, isAdmin, question);

        // 创建带权限过滤和 Reranker 的 RetrievalAugmentor
        RetrievalAugmentor augmentor = createRetrievalAugmentor(userId, isAdmin);

        // 使用官方的 ChatMemoryProvider，自动管理 Redis 存储
        SyncAssistant assistant = AiServices.builder(SyncAssistant.class)
                .chatLanguageModel(chatModel)
                .retrievalAugmentor(augmentor)
                .chatMemoryProvider(chatMemoryProvider)  // 官方 Redis 聊天记忆
                .systemMessageProvider(id -> SYSTEM_PROMPT)
                .build();

        return assistant.chat(sessionId, question);  // sessionId 作为 memoryId
    }

    /**
     * 流式聊天（返回 Flux<String>）
     * 
     * @param sessionId 会话ID（用作 Redis 的 memoryId）
     * @param question 用户问题
     * @return Flux<String> 流式 token
     */
    public Flux<String> chatStream(String sessionId, String question) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        log.info("【RAG Chat Stream】会话ID: {}, 用户ID: {}, 管理员: {}, 问题: {}", 
                 sessionId, userId, isAdmin, question);

        // 创建带权限过滤和 Reranker 的 RetrievalAugmentor
        RetrievalAugmentor augmentor = createRetrievalAugmentor(userId, isAdmin);

        // 暂时不使用 ChatMemoryProvider，避免 Redis 只读问题
        StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatLanguageModel(streamingChatModel)
                .retrievalAugmentor(augmentor)
                .chatMemoryProvider(chatMemoryProvider)
                .systemMessageProvider(id -> SYSTEM_PROMPT)
                .build();

        return assistant.chat(sessionId, question);  // sessionId 作为 memoryId，返回 Flux<String>
    }

    /**
     * 流式聊天（带思考过程）
     * 返回格式：先返回检索到的文档信息（思考过程），再返回 AI 回答
     * 
     * @param sessionId 会话ID
     * @param question 用户问题
     * @return Flux<ThinkingChunk> 包含类型和内容的流
     */
    public Flux<ThinkingChunk> chatStreamWithThinking(String sessionId, String question) {
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        log.info("【RAG Chat Stream (Thinking)】会话ID: {}, 用户ID: {}, 管理员: {}, 问题: {}", 
                 sessionId, userId, isAdmin, question);

        // 1. 手动执行检索过程
        Filter permissionFilter = buildPermissionFilter(userId, isAdmin);
        
        return Flux.defer(() -> {
            try {
                // 2. 向量检索
                EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder retrieverBuilder = 
                    EmbeddingStoreContentRetriever.builder()
                        .embeddingStore(embeddingStore)
                        .embeddingModel(embeddingModel)
                        .maxResults(RECALL_TOP_K)
                        .minScore(0.5);

                if (permissionFilter != null) {
                    retrieverBuilder.filter(permissionFilter);
                }

                EmbeddingStoreContentRetriever contentRetriever = retrieverBuilder.build();
                
                // 执行检索
                var retrievedContents = contentRetriever.retrieve(
                    dev.langchain4j.rag.query.Query.from(question)
                );

                log.info("【RAG 检索】向量检索结果数: {}", retrievedContents.size());
                if (retrievedContents.isEmpty()) {
                    log.warn("【RAG 检索】未检索到任何文档，可能原因：1) 数据库无数据 2) 权限过滤太严格 3) 相似度阈值太高");
                }

                // 3. 手动 Reranking（使用 ScoringModel）
                List<Content> finalContents = new ArrayList<>();
                
                if (!retrievedContents.isEmpty()) {
                    // 提取 TextSegment 用于 rerank
                    List<TextSegment> segments = retrievedContents.stream()
                            .map(content -> content.textSegment())
                            .collect(Collectors.toList());
                    
                    // 执行 rerank
                    Response<List<Double>> scoreResponse = scoringModel.scoreAll(segments, question);
                    List<Double> scores = scoreResponse.content();
                    
                    // 创建带分数的内容列表
                    List<ContentWithScore> contentsWithScores = new ArrayList<>();
                    for (int i = 0; i < retrievedContents.size() && i < scores.size(); i++) {
                        double score = scores.get(i);
                        if (score >= MIN_SCORE) {
                            contentsWithScores.add(new ContentWithScore(retrievedContents.get(i), score));
                        }
                    }
                    
                    // 按分数排序（降序）
                    contentsWithScores.sort((a, b) -> Double.compare(b.score, a.score));
                    
                    // 提取排序后的内容
                    finalContents = contentsWithScores.stream()
                            .map(cws -> cws.content)
                            .collect(java.util.stream.Collectors.toList());
                }

                log.info("【RAG 检索】召回: {}, Rerank后: {}", retrievedContents.size(), finalContents.size());

                // 构建 prompt 和思考过程
                String enhancedPrompt;
                Flux<ThinkingChunk> thinkingFlux;
                
                if (finalContents.isEmpty()) {
                    // 没有检索到文档：允许 AI 使用通用知识回答
                    log.info("【RAG 模式】无相关文档，使用通用知识回答");
                    enhancedPrompt = question;  // 直接使用原始问题
                    thinkingFlux = Flux.just(
                        new ThinkingChunk("thinking", "📄 未检索到相关文档，使用通用知识回答\n")
                    );
                } else {
                    // 有文档：基于文档回答
                    log.info("【RAG 模式】基于 {} 个文档回答", finalContents.size());
                    
                    // 4. 构建思考过程输出
                    thinkingFlux = Flux.concat(
                        Flux.just(new ThinkingChunk("thinking", "📄 检索到的参考文档：\n")),
                        Flux.fromIterable(finalContents)
                            .index()
                            .map(tuple -> {
                                long index = tuple.getT1();
                                var content = tuple.getT2();
                                String fileName = content.textSegment().metadata().getString("filename");
                                if (fileName == null || fileName.isEmpty()) {
                                    fileName = "未知文档";
                                }
                                return new ThinkingChunk("thinking", 
                                    String.format("[%d] %s\n", index + 1, fileName));
                            })
                    );

                    // 5. 构建增强后的 prompt
                    StringBuilder contextBuilder = new StringBuilder();
                    contextBuilder.append("参考以下文档内容回答问题：\n\n");
                    
                    int docIndex = 1;
                    for (var content : finalContents) {
                        contextBuilder.append(String.format("[%d] %s\n\n", 
                            docIndex++, content.textSegment().text()));
                    }
                    
                    contextBuilder.append("\n问题：").append(question);
                    enhancedPrompt = contextBuilder.toString();
                }

                // 6. 流式调用 LLM
                StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                        .streamingChatLanguageModel(streamingChatModel)
                        .chatMemoryProvider(chatMemoryProvider)
                        .systemMessageProvider(id -> SYSTEM_PROMPT)
                        .build();

                Flux<ThinkingChunk> answerFlux = assistant.chat(sessionId, enhancedPrompt)
                        .map(chunk -> new ThinkingChunk("answer", chunk));

                // 7. 合并思考过程和答案
                return Flux.concat(
                    thinkingFlux,
                    Flux.just(new ThinkingChunk("thinking", "\n💭 正在分析并生成回答...\n\n")),
                    answerFlux
                );
                
            } catch (Exception e) {
                log.error("RAG 检索失败", e);
                return Flux.just(new ThinkingChunk("error", "检索失败：" + e.getMessage()));
            }
        });
    }

    /**
     * 思考块数据结构
     */
    public static class ThinkingChunk {
        public String type;  // "thinking" | "answer" | "error"
        public String content;

        public ThinkingChunk(String type, String content) {
            this.type = type;
            this.content = content;
        }
    }

    /**
     * 带分数的内容（用于排序）
     */
    private static class ContentWithScore {
        dev.langchain4j.rag.content.Content content;
        double score;

        ContentWithScore(dev.langchain4j.rag.content.Content content, double score) {
            this.content = content;
            this.score = score;
        }
    }

    /**
     * 创建带权限过滤和 Reranker 的 RetrievalAugmentor
     */
    private RetrievalAugmentor createRetrievalAugmentor(Long userId, boolean isAdmin) {
        // 1. 构建权限过滤器
        Filter permissionFilter = buildPermissionFilter(userId, isAdmin);

        // 2. 创建 ContentRetriever（带权限过滤）
        EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder retrieverBuilder = 
            EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(RECALL_TOP_K)  // 召回 50 个候选
                .minScore(0.5);  // 向量相似度阈值

        if (permissionFilter != null) {
            retrieverBuilder.filter(permissionFilter);
        }

        EmbeddingStoreContentRetriever contentRetriever = retrieverBuilder.build();

        // 3. 创建 ReRankingContentAggregator（Reranker）
        ReRankingContentAggregator contentAggregator = ReRankingContentAggregator.builder()
                .scoringModel(scoringModel)  // 使用 gte-rerank-v2
                .minScore(MIN_SCORE)  // Rerank 分数阈值
                .build();

        // 4. 创建 DefaultRetrievalAugmentor
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentAggregator(contentAggregator)
                .build();
    }

    /**
     * 构建用户权限过滤器
     * 
     * @param userId 用户ID
     * @param isAdmin 是否管理员
     * @return Filter 过滤器（管理员返回 null，普通用户返回权限过滤器）
     */
    private Filter buildPermissionFilter(Long userId, boolean isAdmin) {
        if (isAdmin) {
            log.debug("【权限过滤】管理员模式，无过滤");
            return null;
        }

        // 普通用户：(user_id == 0 && is_public == '1') || user_id == userId
        Filter publicDocs = new And(
            new IsEqualTo("user_id", "0"),
            new IsEqualTo("is_public", "1")
        );

        Filter userDocs = new IsEqualTo("user_id", String.valueOf(userId));

        Filter combinedFilter = new Or(publicDocs, userDocs);

        log.debug("【权限过滤】普通用户模式，user_id: {}", userId);
        return combinedFilter;
    }

    /**
     * 同步 Assistant 接口
     */
    interface SyncAssistant {
        String chat(@MemoryId String memoryId, @UserMessage String userMessage);
    }

    /**
     * 流式 Assistant 接口（返回 Flux<String>）
     */
    interface StreamingAssistant {
        Flux<String> chat(@MemoryId String memoryId, @UserMessage String userMessage);
    }
}
