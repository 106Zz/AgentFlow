package com.agenthub.api.agent_engine.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 模型工厂 (基于 Spring AI Alibaba)
 * <p>负责创建不同角色的 ChatClient，实现多模型协作 (MoE by Architecture)</p>
 *
 * <p>注意：Spring AI Alibaba 默认不会透传 reasoning_content（思考过程）</p>
 * <p>如需获取思考过程，请使用 {@link DashScopeNativeService}</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Configuration
public class AgentModelFactory {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    // ========== 模型配置常量 ==========

    /** Worker 模型配置 */
    public static final String WORKER_MODEL = "deepseek-v3.2";
    public static final double WORKER_TEMPERATURE = 0.7;

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
     * 1. Worker (打工人): 负责日常对话、工具调用
     * <p>模型: deepseek-v3.2 (响应快，成本低)</p>
     * <p>特点: 情商高，指令遵循能力强，适合快速响应</p>
     */
    @Bean("workerChatClient")
    public ChatClient workerChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个电力行业的智能助手，能够协助用户处理业务、查询数据和进行计算。")
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("deepseek-v3.2")
                        .withTemperature(0.7)
                        .build())
                .build();
    }

    /**
     * 2. Judge (大法官): 负责合规审计
     * <p>使用 Spring AI（快速，但无思考过程）</p>
     * <p>如需深度思考过程，请使用 {@link DashScopeNativeService#deepThink}</p>
     */
    @Bean("judgeChatClient")
    public ChatClient judgeChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个冷血的合规审计员。你的任务是基于给定的【事实依据】审查【回答内容】的准确性。")
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("glm-4.7")
                        .withTemperature(0.1)
                        .build())
                .build();
    }

    /**
     * 3. Reader (阅读者): 负责超长文档阅读
     * <p>使用 Spring AI（快速，但无思考过程）</p>
     * <p>如需深度思考过程，请使用 {@link DashScopeNativeService#deepThink}</p>
     */
    @Bean("readerChatClient")
    public ChatClient readerChatClient(ChatClient.Builder builder) {
        return builder
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("kimi-k2-thinking")
                        .withTemperature(0.2)
                        .build())
                .build();
    }

    /**
     * 4. Intent (意图识别): 负责用户意图分类
     * <p>模型: qwen-plus (响应快，分类准确)</p>
     * <p>特点: 低温度设置，确保分类结果稳定一致</p>
     */
    @Bean("intentChatClient")
    public ChatClient intentChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个意图分类助手。负责判断用户问题是闲聊(CHAT)还是需要查询知识库(KB_QA)。")
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .withTemperature(0.1)
                        .build())
                .build();
    }

    /**
     * 5. QueryRewrite (查询改写): 负责将口语化查询改写为正式表达
     * <p>模型: qwen-plus (响应快，改写效果好)</p>
     * <p>特点: 较高温度，增加多样性，覆盖更多改写可能</p>
     */
    @Bean("queryRewriteChatClient")
    public ChatClient queryRewriteChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一个查询改写专家，负责将用户的口语化查询改写为更适合知识库检索的正式表达。")
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .withTemperature(0.7)
                        .build())
                .build();
    }
}
