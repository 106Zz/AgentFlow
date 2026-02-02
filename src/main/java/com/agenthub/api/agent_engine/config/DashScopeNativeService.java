package com.agenthub.api.agent_engine.config;

import com.agenthub.api.ai.domain.llm.DeepThinkResult;
import com.agenthub.api.ai.domain.llm.StreamCallback;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * DashScope 原生 SDK 服务
 * <p>直接使用 DashScope SDK 获取 reasoning_content（思考过程）</p>
 * <p>模型配置统一管理于 {@link AgentModelFactory}</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Slf4j
@Service
public class DashScopeNativeService {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    // ========== 通用方法 ==========

    /**
     * DeepSeek 深度思考（非流式）
     * <p>用于前端展示推理过程</p>
     */
    public DeepThinkResult deepseekThink(String prompt) {
        return deepThink(AgentModelFactory.WORKER_MODEL, prompt, null);
    }

    /**
     * DeepSeek 流式深度思考
     * <p>用于前端实时展示推理过程</p>
     */
    public void deepseekThinkStream(String prompt, StreamCallback callback) {
        deepThinkStream(AgentModelFactory.WORKER_MODEL, prompt, null, callback);
    }

    /**
     * 非流式深度思考调用
     *
     * @param model  模型名称（如 deepseek-r1, glm-4.7, qwen-plus, deepseek-v3.2）
     * @param prompt 用户提示
     * @param system 系统提示（可选）
     * @return 深度思考结果
     */
    public DeepThinkResult deepThink(String model, String prompt, String system) {
        Generation gen = new Generation();

        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(system != null ? system : "你是一个有用的助手。")
                .build();

        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .enableThinking(true)
                .resultFormat("message")
                .messages(Arrays.asList(systemMsg, userMsg))
                .build();

        try {
            GenerationResult result = gen.call(param);
            String reasoning = result.getOutput().getChoices().get(0).getMessage().getReasoningContent();
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();

            return DeepThinkResult.builder()
                    .reasoningContent(reasoning != null ? reasoning : "")
                    .content(content != null ? content : "")
                    .model(model)
                    .build();
        } catch (NoApiKeyException | ApiException | InputRequiredException e) {
            log.error("DashScope 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式深度思考调用
     *
     * @param model     模型名称
     * @param prompt    用户提示
     * @param system    系统提示
     * @param callback  流式回调接口
     */
    public void deepThinkStream(
            String model,
            String prompt,
            String system,
            StreamCallback callback
    ) {
        Generation gen = new Generation();

        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(system != null ? system : "你是一个有用的助手。")
                .build();

        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .enableThinking(true)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(Arrays.asList(systemMsg, userMsg))
                .build();

        try {
            Flowable<GenerationResult> result = gen.streamCall(param);
            result.blockingForEach(chunk -> {
                String reasoning = chunk.getOutput().getChoices().get(0).getMessage().getReasoningContent();
                String content = chunk.getOutput().getChoices().get(0).getMessage().getContent();

                if (reasoning != null && !reasoning.isEmpty()) {
                    callback.onReasoning(reasoning);
                }
                if (content != null && !content.isEmpty()) {
                    callback.onContent(content);
                }
            });
            callback.onComplete();
        } catch (NoApiKeyException | ApiException | InputRequiredException e) {
            log.error("DashScope 流式调用失败: {}", e.getMessage(), e);
            callback.onError(e);
            throw new RuntimeException("AI 流式调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 非流式深度思考调用 (支持多轮对话)
     *
     * @param model    模型名称
     * @param messages 完整的消息列表 (System, User, Assistant...)
     * @return 深度思考结果
     */
    public DeepThinkResult deepThink(String model, java.util.List<Message> messages) {
        // 使用注入的 Generation 实例 (如果想用 Worker 模型，这里可能需要判断一下，或者 factory 提供 workerGeneration)
        // 既然我们决定全走 Native，建议这里 new Generation() 或者注入 workerGeneration
        // 为了简单，且 worker 通常是 DeepSeek，我们这里直接 new，因为 Factory 里 judgeGeneration 是配给 GLM 的
        Generation gen = new Generation();

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .enableThinking(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        try {
            GenerationResult result = gen.call(param);
            String reasoning = result.getOutput().getChoices().get(0).getMessage().getReasoningContent();
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();

            log.info("开始DashScope 调用: {}", result.getOutput().getChoices().get(0).getMessage().getContent());
            return DeepThinkResult.builder()
                    .reasoningContent(reasoning != null ? reasoning : "")
                    .content(content != null ? content : "")
                    .model(model)
                    .build();
        } catch (NoApiKeyException | ApiException | InputRequiredException e) {
            log.error("DashScope 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式深度思考调用 (支持多轮对话)
     *
     * @param model    模型名称
     * @param messages 完整的消息列表
     * @param callback 流式回调接口
     */
    public void deepThinkStream(
            String model,
            java.util.List<Message> messages,
            StreamCallback callback
    ) {
        Generation gen = new Generation();

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .enableThinking(true)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        try {
            Flowable<GenerationResult> result = gen.streamCall(param);
            result.blockingForEach(chunk -> {
                String reasoning = chunk.getOutput().getChoices().get(0).getMessage().getReasoningContent();
                String content = chunk.getOutput().getChoices().get(0).getMessage().getContent();

                // 调试日志：查看模型返回的原始数据
                if (reasoning != null && !reasoning.isEmpty()) {
                    log.info("[DashScope] reasoning length={}, content={}", reasoning.length(), reasoning.substring(0, Math.min(30, reasoning.length())));
                    callback.onReasoning(reasoning);
                }
                if (content != null && !content.isEmpty()) {
                    log.info("[DashScope] content length={}, content={}", content.length(), content.substring(0, Math.min(30, content.length())));
                    callback.onContent(content);
                }
            });
            callback.onComplete();
        } catch (NoApiKeyException | ApiException | InputRequiredException e) {
            log.error("DashScope 流式调用失败: {}", e.getMessage(), e);
            callback.onError(e);
            throw new RuntimeException("AI 流式调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 简化版流式调用（使用默认系统提示）
     *
     * @param model    模型名称
     * @param prompt   用户提示
     * @param callback 流式回调接口
     */
    public void deepThinkStream(String model, String prompt, StreamCallback callback) {
        deepThinkStream(model, prompt, null, callback);
    }
}
