package com.agenthub.api.agent_engine.core.impl;

import com.agenthub.api.agent_engine.capability.CapabilityResolver;
import com.agenthub.api.agent_engine.config.DashScopeNativeService;
import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.core.thinking.StreamDeepThinker;
import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.llm.DeepThinkResult;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.alibaba.dashscope.common.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * DeepSeekChatAgent - 一个基于 DeepSeek 模型的聊天代理实现
 * <p>V2.0: 迁移至 DashScope 原生 SDK，支持深度思考 (Reasoning Content)</p>
 */
@Slf4j
@Service
public class DeepSeekChatAgent implements ChatAgent {

    // ==================== 依赖注入组件 ====================

    private final DashScopeNativeService nativeService;
    private final StreamDeepThinker streamDeepThinker;
    private final CapabilityResolver capabilityResolver;
    private final ObjectMapper objectMapper;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ThreadPoolTaskExecutor agentWorkerExecutor;
    private final ISysPromptService sysPromptService;

    // ==================== 常量定义 ====================

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    private static final String SYSTEM_PROMPT_CODE = "SYSTEM-RAG-v1.0";
    private static final String WORKER_MODEL = "deepseek-v3.2";

    public DeepSeekChatAgent(
            DashScopeNativeService nativeService,
            StreamDeepThinker streamDeepThinker,
            CapabilityResolver capabilityResolver,
            ObjectMapper objectMapper,
            ChatMemoryRepository chatMemoryRepository,
            @Qualifier("agentWorkerExecutor") ThreadPoolTaskExecutor agentWorkerExecutor,
            ISysPromptService sysPromptService) {
        this.nativeService = nativeService;
        this.streamDeepThinker = streamDeepThinker;
        this.capabilityResolver = capabilityResolver;
        this.objectMapper = objectMapper;
        this.chatMemoryRepository = chatMemoryRepository;
        this.agentWorkerExecutor = agentWorkerExecutor;
        this.sysPromptService = sysPromptService;
    }

    @Override
    public Flux<String> stream(AgentContext context) {
        String sessionId = context.getSessionId();
        SecurityContext securityContext = SecurityContextHolder.getContext();

        List<Message> history = loadRecentHistory(sessionId, 10);
        List<AgentTool> tools = capabilityResolver.resolveAvailableTools(context);
        String toolsDesc = tools.stream().map(this::formatToolDesc).collect(Collectors.joining("\n"));

        Map<String, Object> promptVars = new HashMap<>();
        promptVars.put("tools_desc", toolsDesc);
        promptVars.put("user_query", context.getQuery());

        String systemPromptText = sysPromptService.render(SYSTEM_PROMPT_CODE, promptVars);
        if (systemPromptText == null || systemPromptText.isEmpty()) {
            systemPromptText = "You are a helpful assistant. Tools available:\n" + toolsDesc;
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPromptText));
        messages.addAll(history);
        messages.add(new UserMessage(context.getQuery()));

        log.info("[Agent] Start Thinking (Native)... Session: {}", sessionId);

        return Mono.fromCallable(() -> {
                    SecurityContextHolder.setContext(securityContext);
                    try {
                        // 1. 第一轮：非流式思考 (快速判决)
                        List<com.alibaba.dashscope.common.Message> dashMsgs = convertMessages(messages);
                        DeepThinkResult result = nativeService.deepThink(WORKER_MODEL, dashMsgs);

                        log.info("[Agent] Phase 1 Thought: {}", result.getContent());
                        return result;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                })
                .subscribeOn(Schedulers.fromExecutor(agentWorkerExecutor))
                .flatMapMany(thinkResult -> {
                    String thoughtContent = thinkResult.getContent();
                    String jsonToolCall = extractJson(thoughtContent);

                    if (jsonToolCall != null) {
                        // 2. 如果是工具调用 -> 执行工具 -> 流式生成最终回答
                        return executeToolChain(jsonToolCall, tools, context, messages, thoughtContent, securityContext);
                    } else {
                        // 3. 如果不是工具 -> 调用流式深度思考输出
                        List<com.alibaba.dashscope.common.Message> dashMsgs = convertMessages(messages);
                        return streamDeepThinker.think(WORKER_MODEL, dashMsgs, sessionId, context.getQuery());
                    }
                });
    }

    private Flux<String> executeToolChain(String jsonStr, List<AgentTool> tools, AgentContext context, List<Message> contextMessages, String originalThought, SecurityContext securityContext) {
        String toolName = extractToolName(jsonStr);
        String statusMsg = String.format("__TOOL_CALL__:%s\n", toolName);

        Flux<String> statusFlux = Flux.just(statusMsg);

        Flux<String> executionAndAnswerFlux = Mono.fromCallable(() -> {
                    SecurityContextHolder.setContext(securityContext);
                    try {
                        Map<String, Object> callMap = objectMapper.readValue(jsonStr, Map.class);
                        Map<String, Object> args = (Map<String, Object>) callMap.get("args");

                        Optional<AgentTool> toolOpt = tools.stream()
                                .filter(t -> t.getDefinition().getName().equals(toolName))
                                .findFirst();

                        if (toolOpt.isEmpty()) {
                            return ToolExecutionResult.failure("Tool not found: " + toolName);
                        }

                        log.info("[Agent] Executing tool: {}", toolName);
                        return toolOpt.get().execute(ToolExecutionRequest.builder()
                                .toolName(toolName).arguments(args).build(), context);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                })
                .subscribeOn(Schedulers.fromExecutor(agentWorkerExecutor))
                .flatMapMany(result -> {
                    List<Message> newMessages = new ArrayList<>(contextMessages);
                    newMessages.add(new AssistantMessage(originalThought));
                    String toolOutput = result.getOutput();
                    newMessages.add(new UserMessage("工具执行结果: " + toolOutput + "\n请根据结果回答。"));

                    // Prompt 重绘逻辑
                    if (!newMessages.isEmpty() && newMessages.get(0) instanceof SystemMessage) {
                        try {
                            String toolsDesc = tools.stream().map(this::formatToolDesc).collect(Collectors.joining("\n"));
                            Map<String, Object> promptVars = new HashMap<>();
                            promptVars.put("tools_desc", toolsDesc);
                            promptVars.put("user_query", context.getQuery());

                            try {
                                if (toolOutput.trim().startsWith("{") || toolOutput.trim().startsWith("[")) {
                                     Object toolResultObj = objectMapper.readValue(toolOutput, Object.class);
                                     promptVars.put("tool_result", toolResultObj);
                                     if (toolResultObj instanceof Map) {
                                         promptVars.putAll((Map<String, Object>) toolResultObj);
                                     }
                                }
                            } catch (Exception e) {
                                promptVars.put("tool_result_text", toolOutput);
                            }

                            String newSystemPromptText = sysPromptService.render(SYSTEM_PROMPT_CODE, promptVars);
                            if (newSystemPromptText != null && !newSystemPromptText.isEmpty()) {
                                newMessages.set(0, new SystemMessage(newSystemPromptText));
                            }
                        } catch (Exception e) {
                            log.warn("[Agent] Failed to re-render system prompt", e);
                        }
                    }

                    // 转换消息列表
                    List<com.alibaba.dashscope.common.Message> dashMsgs = convertMessages(newMessages);

                    // 3. 第二轮：流式深度思考（使用封装组件）
                    return streamDeepThinker.think(WORKER_MODEL, dashMsgs, context.getSessionId(), context.getQuery());
                });

        return Flux.concat(statusFlux, executionAndAnswerFlux);
    }

    // ==================== 转换器 ====================

    private List<com.alibaba.dashscope.common.Message> convertMessages(List<Message> springMessages) {
        return springMessages.stream().map(msg -> {
            com.alibaba.dashscope.common.Message.MessageBuilder builder = com.alibaba.dashscope.common.Message.builder();

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

    // ==================== 辅助方法 ====================

    private List<Message> loadRecentHistory(String sessionId, int n) {
        List<Message> allMessages = chatMemoryRepository.findByConversationId(sessionId);
        if (allMessages == null) return new ArrayList<>();
        if (allMessages.size() <= n) return allMessages;
        return allMessages.subList(allMessages.size() - n, allMessages.size());
    }

    private String extractJson(String text) {
        if (text == null) return null;
        try {
            Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
            if (matcher.find()) {
                return matcher.group(1);
            }
            if (text.trim().startsWith("{") && text.trim().endsWith("}")) {
                return text;
            }
            int start = text.indexOf("{\"tool\":");
            if (start >= 0) {
                int end = text.lastIndexOf("}");
                if (end > start) {
                    return text.substring(start, end + 1);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractToolName(String json) {
        try {
            return (String) objectMapper.readValue(json, Map.class).get("tool");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String formatToolDesc(AgentTool t) {
        AgentToolDefinition def = t.getDefinition();
        String toolCode = "TOOL-" + def.getName().toUpperCase() + "-v1.0";
        try {
            String dbDesc = sysPromptService.render(toolCode, Collections.emptyMap());
            if (dbDesc != null && !dbDesc.isEmpty()) {
                return String.format("- %s: %s (参数: %s)", def.getName(), dbDesc, def.getParameterSchema());
            }
        } catch (Exception e) { }
        return String.format("- %s: %s (参数: %s)", def.getName(), def.getDescription(), def.getParameterSchema());
    }
}
