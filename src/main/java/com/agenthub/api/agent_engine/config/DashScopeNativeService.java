package com.agenthub.api.agent_engine.config;

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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DashScope 原生 SDK 服务
 * <p>直接使用 DashScope SDK 获取 reasoning_content（思考过程）</p>
 * <p>实现 {@link LLMService} 接口，当 {@code app.llm.provider=dashscope} 时激活</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeNativeService implements LLMService {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    // ========== LLMService 接口实现 ==========

    @Override
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

    @Override
    public DeepThinkResult deepThink(String model, List<org.springframework.ai.chat.messages.Message> messages) {
        Generation gen = new Generation();
        List<Message> dashMessages = convertToDashScopeMessages(messages);

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .enableThinking(true)
                .resultFormat("message")
                .messages(dashMessages)
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

    @Override
    public void deepThinkStream(String model, List<org.springframework.ai.chat.messages.Message> messages,
                                StreamCallback callback) {
        deepThinkStream(model, messages, null, callback);
    }

    @Override
    public void deepThinkStream(String model, List<org.springframework.ai.chat.messages.Message> messages,
                                List<AgentTool> agentTools, StreamCallback callback) {
        Generation gen = new Generation();
        List<Message> dashMessages = convertToDashScopeMessages(messages);

        GenerationParam.GenerationParamBuilder paramBuilder = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .enableThinking(true)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(dashMessages);

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

                            // 累积工具名称和ID
                            if (name != null && !name.isBlank() && currentToolName[0] == null) {
                                currentToolName[0] = name;
                                currentCallId[0] = id != null ? id : "";
                            }

                            // 累积参数（增量追加）
                            if (args != null && !args.isBlank()) {
                                if (accumulatedArgs[0] == null) {
                                    accumulatedArgs[0] = new StringBuilder(args);
                                } else {
                                    String existing = accumulatedArgs[0].toString();
                                    if (!existing.equals(args)) {
                                        if (args.length() > existing.length() && args.startsWith(existing)) {
                                            accumulatedArgs[0] = new StringBuilder(args);
                                        } else if (!existing.startsWith(args)) {
                                            accumulatedArgs[0].append(args);
                                        }
                                    }
                                }
                            }

                            // 当 finish_reason=tool_calls 时，参数已完整，触发回调
                            if ("tool_calls".equals(finishReason)) {
                                String finalToolName = currentToolName[0];
                                String finalArgs = accumulatedArgs[0] != null ? accumulatedArgs[0].toString() : "{}";

                                // 验证参数完整性
                                if (finalArgs != null && !finalArgs.isEmpty() && !finalArgs.equals("{}")) {
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
                                    List<ToolCall> myToolCalls = new ArrayList<>();
                                    myToolCalls.add(ToolCall.builder()
                                            .callId(currentCallId[0] != null ? currentCallId[0] : "")
                                            .toolName(finalToolName)
                                            .parameters(finalArgs != null && !finalArgs.isBlank() ? finalArgs : "{}")
                                            .build());
                                    callback.onToolCall(myToolCalls);
                                }

                                // 重置累积器
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

    // ========== 内部工具方法 ==========

    /**
     * Spring AI Message → DashScope Message 转换
     * <p>从 SinglePassExecutor 移入，统一在此处理格式差异</p>
     */
    private List<Message> convertToDashScopeMessages(
            List<org.springframework.ai.chat.messages.Message> springMessages) {
        return springMessages.stream().map(msg -> {
            Message.MessageBuilder builder = Message.builder();

            if (msg instanceof SystemMessage) {
                builder.role(Role.SYSTEM.getValue());
            } else if (msg instanceof UserMessage) {
                builder.role(Role.USER.getValue());
            } else if (msg instanceof AssistantMessage) {
                builder.role(Role.ASSISTANT.getValue());
            } else {
                builder.role(Role.USER.getValue());
            }

            builder.content(msg.getText());
            return builder.build();
        }).collect(Collectors.toList());
    }

    /**
     * AgentTool → DashScope FunctionDefinition
     */
    private FunctionDefinition convertToFunctionDefinition(AgentTool tool) {
        try {
            AgentToolDefinition def = tool.getDefinition();
            String desc = def.getDescription();
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
