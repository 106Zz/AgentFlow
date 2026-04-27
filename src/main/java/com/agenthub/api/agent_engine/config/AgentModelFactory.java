package com.agenthub.api.agent_engine.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Agent 模型工厂
 * <p>负责集中管理 DashScope 辅助角色的模型配置和 ChatClient Bean</p>
 * <p>Worker 角色已切换为本地 Ollama 双模型（基座+微调），通过配置文件 + LLMService 调用</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Configuration
public class AgentModelFactory {

    // ========== DashScope 云端模型配置 ==========

    /** Judge 模型配置 */
    public static final String JUDGE_MODEL = "glm-4.7";
    public static final double JUDGE_TEMPERATURE = 0.1;

    /** Reader 模型配置 */
    public static final String READER_MODEL = "kimi-k2-thinking";
    public static final double READER_TEMPERATURE = 0.2;

    /** Intent 意图识别模型配置 */
    public static final String INTENT_MODEL = "qwen-plus";
    public static final double INTENT_TEMPERATURE = 0.1;

    /** QueryRewrite 查询改写模型配置 */
    public static final String QUERY_REWRITE_MODEL = "qwen-plus";
    public static final double QUERY_REWRITE_TEMPERATURE = 0.7;

    /**
     * 将 DashScope ChatModel 设为 @Primary
     * <p>解决 DashScope + Ollama 双 ChatModel 共存时的冲突：
     * ChatClient.Builder 自动注入需要唯一的 ChatModel</p>
     * <p>所有 AgentModelFactory 创建的 ChatClient 都使用 DashScope ChatModel</p>
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(@Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
        return dashScopeChatModel;
    }

    /**
     * 1. Judge (大法官): 负责合规审计
     */
    @Bean("judgeChatClient")
    public ChatClient judgeChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个冷血的合规审计员。你的任务是基于给定的【事实依据】审查【回答内容】的准确性。")
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(JUDGE_MODEL)
                        .withTemperature(JUDGE_TEMPERATURE)
                        .build())
                .build();
    }

    /**
     * Reader (阅读者): 负责超长文档阅读
     */
    @Bean("readerChatClient")
    public ChatClient readerChatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(READER_MODEL)
                        .withTemperature(READER_TEMPERATURE)
                        .build())
                .build();
    }

    /**
     * Intent (意图识别): 负责用户意图分类
     */
    @Bean("intentChatClient")
    public ChatClient intentChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个意图分类助手。负责判断用户问题是闲聊(CHAT)还是需要查询知识库(KB_QA)。")
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(INTENT_MODEL)
                        .withTemperature(INTENT_TEMPERATURE)
                        .build())
                .build();
    }

    /**
     * QueryRewrite (查询改写): 负责将口语化查询改写为正式表达
     */
    @Bean("queryRewriteChatClient")
    public ChatClient queryRewriteChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个查询改写专家，负责将用户的口语化查询改写为更适合知识库检索的正式表达。")
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(QUERY_REWRITE_MODEL)
                        .withTemperature(QUERY_REWRITE_TEMPERATURE)
                        .build())
                .build();
    }
}
