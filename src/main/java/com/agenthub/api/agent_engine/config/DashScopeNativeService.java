package com.agenthub.api.agent_engine.config;

import cn.hutool.json.JSONObject;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolCall;
import com.agenthub.api.agent_engine.tool.AgentTool;
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
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
     * 流式深度思考调用 (支持工具，自动适配转换)
     *
     * @param model    模型名称
     * @param messages 完整的消息列表
     * @param agentTools 业务层工具列表
     * @param callback 流式回调接口
     */
    public void deepThinkStream(
            String model,
            java.util.List<Message> messages,
            List<AgentTool> agentTools,
            StreamCallback callback
    ) {
        Generation gen = new Generation();

        GenerationParam.GenerationParamBuilder paramBuilder = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .enableThinking(true)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages);

        // 内部适配：将 AgentTool 转换为 DashScope FunctionDefinition
        if (agentTools != null && !agentTools.isEmpty()) {
            List<ToolFunction> toolFunctions = agentTools.stream()
                    .map(this::convertToFunctionDefinition)
                    .filter(java.util.Objects::nonNull)
                    .map(f -> ToolFunction.builder().function(f).build())
                    .collect(Collectors.toList());
            paramBuilder.tools(toolFunctions);
        }

        GenerationParam param = paramBuilder.build();

        try {
            Flowable<GenerationResult> result = gen.streamCall(param);

            // 用于累积流式工具调用参数
            // DeepSeek 在 incrementalOutput=true 模式下，参数是分段返回的
            final StringBuilder[] accumulatedArgs = new StringBuilder[1];
            final String[] currentToolName = new String[1];
            final String[] currentCallId = new String[1];

            result.blockingForEach(chunk -> {
                var choice = chunk.getOutput().getChoices().get(0);
                var message = choice.getMessage();

                String reasoning = message.getReasoningContent();
                String content = message.getContent();
                List<ToolCallBase> toolCalls = message.getToolCalls();

                // 1. 处理思考过程
                if (reasoning != null && !reasoning.isEmpty()) {
                    callback.onReasoning(reasoning);
                }

                // 2. 处理内容
                if (content != null && !content.isEmpty()) {
                    callback.onContent(content);
                }

                // 3. 处理工具调用
                // DeepSeek 流式模式：参数是分段返回的，需要累积
                boolean hasToolCallFromApi = toolCalls != null && !toolCalls.isEmpty();

                if (hasToolCallFromApi) {
                    for (ToolCallBase tc : toolCalls) {
                        if (tc instanceof ToolCallFunction tcf) {
                            String id = tcf.getId();
                            String name = tcf.getFunction() != null ? tcf.getFunction().getName() : null;
                            String args = tcf.getFunction() != null ? tcf.getFunction().getArguments() : null;
                            String finishReason = choice.getFinishReason();

                            log.debug("[DashScope] 工具调用块: id={}, name={}, args={}, finishReason={}",
                                    id, name, args != null ? args.substring(0, Math.min(50, args.length())) : "null", finishReason);

                            // 累积工具名称和ID（从第一个非空块获取）
                            if (name != null && !name.isBlank() && currentToolName[0] == null) {
                                currentToolName[0] = name;
                                currentCallId[0] = id != null ? id : "";
                            }

                            // 累积参数（增量追加）
                            if (args != null && !args.isBlank()) {
                                if (accumulatedArgs[0] == null) {
                                    accumulatedArgs[0] = new StringBuilder(args);
                                } else {
                                    // 增量追加：跳过已经包含的部分
                                    String existing = accumulatedArgs[0].toString();
                                    if (!existing.equals(args)) {
                                        // 简单的增量追加逻辑
                                        if (args.length() > existing.length() && args.startsWith(existing)) {
                                            accumulatedArgs[0] = new StringBuilder(args);
                                        } else if (!existing.startsWith(args)) {
                                            // 如果新内容不是已累积内容的前缀，直接追加
                                            accumulatedArgs[0].append(args);
                                        }
                                    }
                                }
                            }

                            // 当 finish_reason=tool_calls 时，参数已完整，触发回调
                            if ("tool_calls".equals(finishReason)) {
                                String finalToolName = currentToolName[0];
                                String finalArgs = accumulatedArgs[0] != null ? accumulatedArgs[0].toString() : "{}";

                                // 验证参数完整性，确保以 { 开头和 } 结尾
                                if (finalArgs != null && !finalArgs.isEmpty() && !finalArgs.equals("{}")) {
                                    // 确保是有效的 JSON
                                    if (!finalArgs.startsWith("{")) {
                                        finalArgs = "{" + finalArgs;
                                    }
                                    if (!finalArgs.endsWith("}")) {
                                        finalArgs = finalArgs + "}";
                                    }
                                }

                                log.info("[DashScope] 工具调用完成: tool={}, args={}, callId={}",
                                        finalToolName, finalArgs, currentCallId[0]);

                                if (finalToolName != null && !finalToolName.isBlank()) {
                                    List<com.agenthub.api.agent_engine.model.ToolCall> myToolCalls = new ArrayList<>();
                                    myToolCalls.add(ToolCall.builder()
                                            .callId(currentCallId[0] != null ? currentCallId[0] : "")
                                            .toolName(finalToolName)
                                            .parameters(finalArgs != null && !finalArgs.isBlank() ? finalArgs : "{}")
                                            .build());
                                    callback.onToolCall(myToolCalls);
                                }

                                // 重置累积器，支持后续可能的工具调用
                                accumulatedArgs[0] = null;
                                currentToolName[0] = null;
                                currentCallId[0] = null;
                            }
                        }
                    }
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
     * 内部转换逻辑：AgentTool -> FunctionDefinition
     */
    private FunctionDefinition convertToFunctionDefinition(AgentTool tool) {
        try {
            AgentToolDefinition def = tool.getDefinition();
            String desc = def.getDescription();
            // 简单的描述清洗
            if (desc != null && desc.contains("(参数:")) {
                desc = desc.substring(0, desc.indexOf("(参数:")).trim();
            }
            
            return FunctionDefinition.builder()
                    .name(def.getName())
                    .description(desc)
                    .parameters(JsonUtils.parseString(def.getParameterSchema()).getAsJsonObject())
                    .build();
        } catch (Exception e) {
            log.warn("[DashScopeNativeService] 工具转换失败: {}", tool.getDefinition().getName(), e);
            return null;
        }
    }
}
