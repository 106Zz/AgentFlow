package com.agenthub.api.ai.usecase;

import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeTool;
import com.agenthub.api.knowledge.domain.vo.StreamChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 聊天用例 (ChatUseCase)
 * 职责: 处理通用对话 (CHAT) 意图，集成 RAG、记忆、角色扮演等高级能力。
 * 它是 "Smart" Client 的主场。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatUseCase implements AIUseCase {

    private final PowerKnowledgeTool knowledgeTool;
    private final ChatModel chatModel;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ObjectMapper objectMapper;

    @Value("classpath:/prompts/rag-system-prompt.st")
    private Resource systemPromptResource;

    @Override
    public boolean support(String intent) {
        return "CHAT".equals(intent) || intent == null || intent.isEmpty();
    }

    @Override
    public AIResponse execute(AIRequest request) {
        log.info(">>>> [UseCase] 进入通用 RAG 问答模式 (Stream + Reasoning): {}", request.query());
        
        String conversationId = request.sessionId(); 
        // 这里显式使用了 this.chatMemoryRepository 构建 Advisor
        // 这就是为什么注入生效的原因：我们手动把注入进来的 Repo 塞给了 Client
        ChatClient smartClient = buildSmartClient(conversationId);

        PowerKnowledgeQuery searchArgs = new PowerKnowledgeQuery(request.query(), 3, null, null);
        PowerKnowledgeResult searchResult = knowledgeTool.retrieve(searchArgs);
        String context = searchResult.answer();

        // 改为获取完整 ChatResponse 以提取思考过程
        Flux<String> stream = smartClient.prompt()
                .system(s -> s.param("context", context)) 
                .user(request.query())
                .stream()
                .chatResponse() // <--- 关键变化：获取完整响应对象
                .map(resp -> {
                    // 1. 提取思考过程 (DeepSeek R1 等)
                    String reasoning = null;
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        Map<String, Object> meta = resp.getResult().getOutput().getMetadata();
                        if (meta != null) {
                            Object t = meta.get("reasoning_content");
                            if (t == null) t = meta.get("reasoning");
                            if (t != null) reasoning = t.toString();
                        }
                    }

                    // 2. 提取正文
                    String content = "";
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        content = resp.getResult().getOutput().getText();
                    }

                    // 3. 包装为 StreamChunk 并转为 JSON 字符串
                    // 前端接收到的是: {"reasoning":"...", "content":"...", "sessionId":"..."}
                    try {
                        return objectMapper.writeValueAsString(new StreamChunk(reasoning, content, conversationId));
                    } catch (Exception e) {
                        log.warn("JSON序列化失败", e);
                        return "{}";
                    }
                });

        return AIResponse.ofStream(stream);
    }

    private ChatClient buildSmartClient(String conversationId) {
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPromptResource)
                .defaultAdvisors(
                        // 在这里显式注入了 chatMemoryRepository
                        MessageChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .chatMemoryRepository(chatMemoryRepository) // <--- 就是这里！
                                        .maxMessages(20)
                                        .build()
                        )
                        .conversationId(conversationId)
                        .build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }
}
