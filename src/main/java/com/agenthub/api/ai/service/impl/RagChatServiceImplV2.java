package com.agenthub.api.ai.service.impl;

import com.agenthub.api.common.utils.SecurityUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 聊天服务实现 (LangChain4j 版本)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagChatServiceImplV2 {

    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

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
     */
    public String chat(String sessionId, String question) {
        log.info("【RAG Chat】会话ID: {}, 问题: {}", sessionId, question);
        
        // 1. 检索相关文档
        List<TextSegment> relevantDocs = retrieveRelevantDocuments(question, 5);
        
        // 2. 构建上下文
        String context = buildContext(relevantDocs);
        
        // 3. 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        
        if (!context.isEmpty()) {
            messages.add(new SystemMessage("## 检索到的知识库内容：\n\n" + context));
        }
        
        messages.add(new UserMessage(question));
        
        // 4. 调用模型
        Response<AiMessage> response = chatModel.generate(messages);
        
        return response.content().text();
    }

    /**
     * 流式聊天（简化版，暂不支持流式）
     */
    public Flux<String> chatStream(String sessionId, String question) {
        // LangChain4j 的流式需要使用 StreamingChatLanguageModel
        // 这里先返回同步结果的 Flux 包装
        String result = chat(sessionId, question);
        return Flux.just(result);
    }

    /**
     * 检索相关文档
     */
    private List<TextSegment> retrieveRelevantDocuments(String query, int maxResults) {
        try {
            // 1. 将查询转换为向量
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            
            // 2. 搜索相似文档
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults)
                    .minScore(0.5) // 最小相似度阈值
                    .build();
            
            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            
            // 3. 提取文档
            return searchResult.matches().stream()
                    .map(EmbeddingMatch::embedded)
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            log.error("检索文档失败", e);
            return List.of();
        }
    }

    /**
     * 构建上下文
     */
    private String buildContext(List<TextSegment> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }

        return documents.stream()
                .map(doc -> {
                    String filename = doc.metadata("filename") != null 
                            ? doc.metadata("filename").toString() 
                            : "未知文件";
                    String content = doc.text();
                    return String.format("【文件：%s】\n%s", filename, content);
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
