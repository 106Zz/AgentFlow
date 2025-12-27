package com.agenthub.api.ai.service.impl;

import com.agenthub.api.common.utils.SecurityUtils;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Or;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
        你是一位面向企业客户的专业知识库分析师，能够基于知识库数据提供高质量、专业级的解答。
        
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
        用自然段落提供完整、详细的回答。
        
        如果知识库完全没有相关信息，在最终答案中写：抱歉，当前知识库暂未收录该内容。
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

        // 使用官方的 ChatMemoryProvider，自动管理 Redis 存储
        StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatLanguageModel(streamingChatModel)
                .retrievalAugmentor(augmentor)
                .chatMemoryProvider(chatMemoryProvider)  // 官方 Redis 聊天记忆
                .systemMessageProvider(id -> SYSTEM_PROMPT)
                .build();

        return assistant.chat(sessionId, question);  // sessionId 作为 memoryId，返回 Flux<String>
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
        String chat(String memoryId, String userMessage);
    }

    /**
     * 流式 Assistant 接口（返回 Flux<String>）
     */
    interface StreamingAssistant {
        Flux<String> chat(String memoryId, String userMessage);
    }
}
