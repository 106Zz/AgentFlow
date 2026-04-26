package com.agenthub.api.agent_engine.config;

import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.llm.DeepThinkResult;
import com.agenthub.api.ai.domain.llm.StreamCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Ollama 本地模型服务
 * <p>实现 {@link LLMService} 接口，当 {@code app.llm.provider=ollama} 时激活</p>
 * <p>使用 Spring AI Ollama 自动配置的 {@link OllamaChatModel}</p>
 *
 * <h3>环境要求:</h3>
 * <ul>
 *   <li>Ollama 0.2.8+ — 基础对话</li>
 *   <li>Ollama 0.4.6+ — 流式 Function Calling</li>
 * </ul>
 *
 * @author AgentHub
 * @since 2026-04-25
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "ollama")
public class    OllamaLLMService implements LLMService {

    private final OllamaChatModel ollamaChatModel;

    public OllamaLLMService(OllamaChatModel ollamaChatModel) {
        this.ollamaChatModel = ollamaChatModel;
        log.info("[Ollama] OllamaLLMService 已激活，本地模型服务就绪");
    }

    // ========== LLMService 接口实现 ==========

    @Override
    public DeepThinkResult deepThink(String model, String prompt, String system) {
        List<Message> messages = new ArrayList<>();
        if (system != null) {
            messages.add(new SystemMessage(system));
        } else {
            messages.add(new SystemMessage("你是一个有用的助手。"));
        }
        messages.add(new UserMessage(prompt));
        return deepThink(model, messages);
    }

    @Override
    public DeepThinkResult deepThink(String model, List<Message> messages) {
        OllamaOptions options = OllamaOptions.builder()
                .model(model)
                .build();

        Prompt prompt = new Prompt(messages, options);

        try {
            ChatResponse response = ollamaChatModel.call(prompt);

            String thinking = extractThinking(response);
            String content = response.getResult() != null
                    ? response.getResult().getOutput().getText()
                    : "";

            return DeepThinkResult.builder()
                    .reasoningContent(thinking != null ? thinking : "")
                    .content(content != null ? content : "")
                    .model(model)
                    .build();
        } catch (Exception e) {
            log.error("[Ollama] 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deepThinkStream(String model, List<Message> messages, StreamCallback callback) {
        deepThinkStream(model, messages, null, callback);
    }

    @Override
    public void deepThinkStream(String model, List<Message> messages,
                                List<AgentTool> agentTools, StreamCallback callback) {
        OllamaOptions options = OllamaOptions.builder()
                .model(model)
                .temperature(0.7)
                .build();

        Prompt prompt = new Prompt(messages, options);

        try {
            ollamaChatModel.stream(prompt)
                    .subscribe(
                            response -> handleStreamChunk(response, callback),
                            error -> {
                                log.error("[Ollama] 流式调用失败: {}", error.getMessage(), error);
                                callback.onError(error);
                            },
                            callback::onComplete
                    );
        } catch (Exception e) {
            log.error("[Ollama] 流式调用失败: {}", e.getMessage(), e);
            callback.onError(e);
            throw new RuntimeException("AI 流式调用失败: " + e.getMessage(), e);
        }
    }

    // ========== 内部方法 ==========

    /**
     * 处理流式响应的单个 chunk
     */
    private void handleStreamChunk(ChatResponse response, StreamCallback callback) {
        if (response == null || response.getResult() == null) {
            return;
        }

        // 1. 处理思考内容
        String thinking = extractThinking(response);
        if (thinking != null && !thinking.isEmpty()) {
            callback.onReasoning(thinking);
        }

        // 2. 处理正文内容
        String content = response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        if (content != null && !content.isEmpty()) {
            callback.onContent(content);
        }
    }

    /**
     * 从 ChatResponse 中提取思考内容
     * <p>思考模型 (qwen3 / deepseek-r1) 会通过 metadata 中的 "thinking" 字段返回思考过程</p>
     * <p>1.1.0-M4 版本中 ChatGenerationMetadata 通过 get(key) 获取扩展字段</p>
     */
    private String extractThinking(ChatResponse response) {
        if (response.getResult() != null && response.getResult().getMetadata() != null) {
            var metadata = response.getResult().getMetadata();
            Object thinking = metadata.get("thinking");
            if (thinking != null) {
                return thinking.toString();
            }
        }
        return null;
    }
}
