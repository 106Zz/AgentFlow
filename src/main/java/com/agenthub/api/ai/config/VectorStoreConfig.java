package com.agenthub.api.ai.config;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.embedding.EmbeddingModel;

@Configuration
public class VectorStoreConfig {

    @Bean
    public PgVectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .indexType(PgVectorStore.PgIndexType.HNSW) // 默认使用 HNSW 索引
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE) // 默认使用余弦距离
                .dimensions(1536) // 默认维度，根据实际模型调整
                .removeExistingVectorStoreTable(false)
                .initializeSchema(true)
                .build();
    }
}
