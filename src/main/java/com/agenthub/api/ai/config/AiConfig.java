package com.agenthub.api.ai.config;

import org.springframework.context.annotation.Configuration;

/**
 * AI配置类
 * 用于配置Spring AI相关的Bean
 */
@Configuration
public class AiConfig {

    // TODO: 配置 ChatClient、EmbeddingModel、VectorStore 等Bean
    // 这些配置根据你使用的AI服务（DashScope、OpenAI等）来定制
    
    // 示例：
    // @Bean
    // public ChatClient chatClient(ChatModel chatModel) {
    //     return ChatClient.builder(chatModel).build();
    // }
}
