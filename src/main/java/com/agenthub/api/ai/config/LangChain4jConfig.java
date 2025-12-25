package com.agenthub.api.ai.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 配置类
 */
@Slf4j
@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.dashscope.api-key}")
    private String apiKey;

    @Value("${langchain4j.dashscope.chat-model.model-name:qwen-max}")
    private String chatModelName;

    @Value("${langchain4j.dashscope.chat-model.temperature:0.7}")
    private Double temperature;

    @Value("${langchain4j.dashscope.chat-model.max-tokens:2000}")
    private Integer maxTokens;

    @Value("${langchain4j.dashscope.embedding-model.model-name:text-embedding-v3}")
    private String embeddingModelName;

    @Value("${langchain4j.pgvector.host}")
    private String pgHost;

    @Value("${langchain4j.pgvector.port}")
    private Integer pgPort;

    @Value("${langchain4j.pgvector.database}")
    private String pgDatabase;

    @Value("${langchain4j.pgvector.user}")
    private String pgUser;

    @Value("${langchain4j.pgvector.password}")
    private String pgPassword;

    @Value("${langchain4j.pgvector.dimension:1536}")
    private Integer dimension;

    @Value("${langchain4j.pgvector.table:langchain4j_embeddings}")
    private String tableName;

    @Value("${langchain4j.pgvector.create-table:true}")
    private Boolean createTable;

    /**
     * 通义千问聊天模型
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("初始化 ChatLanguageModel: {}", chatModelName);
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(chatModelName)
                .temperature(temperature.floatValue())
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * 通义千问 Embedding 模型
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("初始化 EmbeddingModel: {}", embeddingModelName);
        return QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .build();
    }

    /**
     * PGVector 向量存储
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("初始化 PGVector EmbeddingStore: {}:{}/{}", pgHost, pgPort, pgDatabase);
        
        return PgVectorEmbeddingStore.builder()
                .host(pgHost)
                .port(pgPort)
                .database(pgDatabase)
                .user(pgUser)
                .password(pgPassword)
                .table(tableName)
                .dimension(dimension)
                .createTable(createTable)
                .dropTableFirst(false)
                .build();
    }
}
