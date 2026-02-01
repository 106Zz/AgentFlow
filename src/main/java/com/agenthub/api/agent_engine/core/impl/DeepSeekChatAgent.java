package com.agenthub.api.agent_engine.core.impl;

import com.agenthub.api.agent_engine.capability.CapabilityResolver;
import com.agenthub.api.agent_engine.core.ChatAgent;
import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于 DeepSeek 模型实现的 ChatAgent (V3.0 深度思考版)
 * 特性：使用 <think> 标签包裹工具调用状态，便于前端渲染为"思考过程"
 */
@Slf4j
@Service
public class DeepSeekChatAgent implements ChatAgent {

    private final ChatClient chatClient;
    private final CapabilityResolver capabilityResolver;
    private final ObjectMapper objectMapper;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ThreadPoolTaskExecutor agentWorkerExecutor;

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个电力行业的智能助手。你可以使用以下工具来解决用户的问题：
            
            %s
            
            请严格遵循以下规则：
            1. 如果用户的问题需要查询数据（如查电价、政策、计算），**必须**先调用工具。
            2. 调用工具时，只输出一个 JSON 对象，不要输出任何其他解释文字。格式：{\"tool\": \"xxx\", \"args\": {...}}
            3. 如果不需要工具，直接回答用户问题。
            
            """;

    public DeepSeekChatAgent(
            ChatClient chatClient,
            CapabilityResolver capabilityResolver,
            ObjectMapper objectMapper,
            ChatMemoryRepository chatMemoryRepository,
            @Qualifier("agentWorkerExecutor") ThreadPoolTaskExecutor agentWorkerExecutor) {
        this.chatClient = chatClient;
        this.capabilityResolver = capabilityResolver;
        this.objectMapper = objectMapper;
        this.chatMemoryRepository = chatMemoryRepository;
        this.agentWorkerExecutor = agentWorkerExecutor;
    }

    @Override
    public Flux<String> stream(AgentContext context) {
        String sessionId = context.getSessionId();
        SecurityContext securityContext = SecurityContextHolder.getContext();
        
        List<Message> history = loadRecentHistory(sessionId, 10);
        List<AgentTool> tools = capabilityResolver.resolveAvailableTools(context);
        String toolsDesc = tools.stream().map(this::formatToolDesc).collect(Collectors.joining("\n"));
        
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(String.format(SYSTEM_PROMPT_TEMPLATE, toolsDesc)));
        messages.addAll(history);
        messages.add(new UserMessage(context.getQuery()));
        
        log.info("[Agent] Start Thinking... Session: {}", sessionId);

        return Mono.fromCallable(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                String thought = chatClient.prompt().messages(messages).call().content();
                log.info("[Agent] Raw Thought: {}", thought);
                return thought;
            } finally {
                SecurityContextHolder.clearContext();
            }
        })
        .subscribeOn(Schedulers.fromExecutor(agentWorkerExecutor))
        .flatMapMany(thought -> {
            String jsonToolCall = extractJson(thought);
            if (jsonToolCall != null) {
                return executeToolChain(jsonToolCall, tools, context, messages, thought, securityContext);
            } else {
                saveMemory(sessionId, context.getQuery(), thought);
                return Flux.just(thought);
            }
        });
    }
    
    private Flux<String> executeToolChain(String jsonStr, List<AgentTool> tools, AgentContext context, List<Message> contextMessages, String originalThought, SecurityContext securityContext) {
        String toolName = extractToolName(jsonStr);
        // Change: Wrap status in <think> tags
        String statusMsg = String.format("<think>正在调用工具 `%s` ...</think>\n\n", toolName); // Corrected escaping for backticks
        
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
            newMessages.add(new UserMessage("工具执行结果: " + result.getOutput() + "\n请根据结果回答。"));
            
            StringBuffer replyBuffer = new StringBuffer();
            return chatClient.prompt() 
                    .messages(newMessages)
                    .stream()
                    .content()
                    .doOnNext(replyBuffer::append)
                    .doOnComplete(() -> {
                        saveMemory(context.getSessionId(), context.getQuery(), replyBuffer.toString());
                    });
        });
        
        return Flux.concat(statusFlux, executionAndAnswerFlux);
    }
    
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
        return String.format("- %s: %s (参数: %s)", def.getName(), def.getDescription(), def.getParameterSchema());
    }
}
