package com.agenthub.api.agent_engine.core.impl;

import com.agenthub.api.agent_engine.capability.CapabilityResolver;
import com.agenthub.api.agent_engine.config.DashScopeNativeService;
import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.llm.DeepThinkResult;
import com.agenthub.api.ai.domain.llm.StreamCallback;
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
            CapabilityResolver capabilityResolver,
            ObjectMapper objectMapper,
            ChatMemoryRepository chatMemoryRepository,
            @Qualifier("agentWorkerExecutor") ThreadPoolTaskExecutor agentWorkerExecutor,
            ISysPromptService sysPromptService) {
        this.nativeService = nativeService;
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
                        // 这里我们使用 convertMessages 将 Spring Message 转为 DashScope Message
                        List<com.alibaba.dashscope.common.Message> dashMsgs = convertMessages(messages);
                        DeepThinkResult result = nativeService.deepThink(WORKER_MODEL, dashMsgs);
                        
                        log.info("[Agent] Phase 1 Thought: {}", result.getContent());
                        // 如果有 reasoning，也可以 log 一下，或者推给前端 (看需求)
                        return result;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                })
                .subscribeOn(Schedulers.fromExecutor(agentWorkerExecutor))
                .flatMapMany(thinkResult -> {
                    String thoughtContent = thinkResult.getContent(); // 主要看 content 里的 JSON
                    String jsonToolCall = extractJson(thoughtContent);
                    
                    if (jsonToolCall != null) {
                        // 2. 如果是工具调用 -> 执行工具 -> 流式生成最终回答
                        return executeToolChain(jsonToolCall, tools, context, messages, thoughtContent, securityContext);
                    } else {
                        // 3. 如果不是工具 -> 直接把刚才拿到的结果返回
                        // 这里有个小问题：deepThink 是非流式的，我们已经拿到了完整结果。
                        // 为了保持接口一致性，我们把它包装成 Flux
                        // TODO: 如果想让前端看到第一阶段的 Reasoning，这里应该把 thinkResult.getReasoningContent() 也发出去
                        // 格式协议: "__THINK__:" + reasoning + "\n" + content
                        String output = "";
                        if (thinkResult.getReasoningContent() != null && !thinkResult.getReasoningContent().isEmpty()) {
                            output += "__THINK__:" + thinkResult.getReasoningContent() + "\n";
                        }
                        output += thoughtContent;
                        
                        saveMemory(sessionId, context.getQuery(), thoughtContent);
                        return Flux.just(output);
                    }
                });
    }

    private Flux<String> executeToolChain(String jsonStr, List<AgentTool> tools, AgentContext context, List<Message> contextMessages, String originalThought, SecurityContext securityContext) {
        String toolName = extractToolName(jsonStr);
        // 推送工具调用状态 (前端可解析)
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

                    // Prompt 重绘逻辑 (保持不变)
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

                    // 3. 第二轮：流式深度思考
                    return Flux.create(sink -> {
                        StringBuffer fullContent = new StringBuffer();
                        StringBuffer fullReasoning = new StringBuffer(); // 新增：收集思考过程
                        
                        // 状态标记 (原子引用以在 lambda 中使用，或者使用 final 数组)
                        final boolean[] state = new boolean[]{false, false}; // [0]=hasStartedThinking, [1]=hasEndedThinking
                        
                        nativeService.deepThinkStream(WORKER_MODEL, dashMsgs, new StreamCallback() {
                            @Override
                            public void onReasoning(String reasoning) {
                                if (!state[0]) {
                                    sink.next("@@THINK_START@@");
                                    state[0] = true;
                                }
                                fullReasoning.append(reasoning); // 收集思考
                                sink.next(reasoning);
                            }

                            @Override
                            public void onContent(String content) {
                                // 如果之前在思考，现在切到正文了，且还没发过结束标记
                                if (state[0] && !state[1]) {
                                    sink.next("@@THINK_END@@");
                                    state[1] = true;
                                }
                                fullContent.append(content);
                                sink.next(content);
                            }

                            @Override
                            public void onComplete() {
                                // 兜底：如果结束了还在思考状态（可能没有 Content），发一个结束标记
                                if (state[0] && !state[1]) {
                                    sink.next("@@THINK_END@@");
                                }
                                
                                // 构建包含思考过程的完整记录，用于持久化
                                // 格式: <think>思考内容</think>\n\n正文内容
                                String finalAnswer = "";
                                if (fullReasoning.length() > 0) {
                                    finalAnswer += "<think>" + fullReasoning.toString() + "</think>\n\n";
                                }
                                finalAnswer += fullContent.toString();
                                
                                saveMemory(context.getSessionId(), context.getQuery(), finalAnswer);
                                sink.complete();
                            }

                            @Override
                            public void onError(Throwable e) {
                                sink.error(e);
                            }
                        });
                    });
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
                builder.role(Role.USER.getValue()); // 默认 fallback
            }
            
            builder.content(msg.getText());
            return builder.build();
        }).collect(Collectors.toList());
    }

    // ==================== 原有辅助方法 (保持不变) ====================

    private List<Message> loadRecentHistory(String sessionId, int n) {
        List<Message> allMessages = chatMemoryRepository.findByConversationId(sessionId);
        if (allMessages == null) return new ArrayList<>();
        if (allMessages.size() <= n) return allMessages;
        return allMessages.subList(allMessages.size() - n, allMessages.size());
    }

    private void saveMemory(String sessionId, String query, String reply) {
        try {
            List<Message> current = chatMemoryRepository.findByConversationId(sessionId);
            if (current == null) current = new ArrayList<>();
            current.add(new UserMessage(query));
            current.add(new AssistantMessage(reply));
            chatMemoryRepository.saveAll(sessionId, current);
        } catch (Exception e) {
            log.error("Memory save failed", e);
        }
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
