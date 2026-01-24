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

        // 🔧 修复：将实际的文档内容片段拼接为完整的上下文，而不是只用摘要
        String context = buildRagContext(searchResult);

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

    /**
     * 构建完整的 RAG 上下文
     * 将检索到的文档片段和来源信息拼接为结构化的上下文字符串
     *
     * @param searchResult 检索结果
     * @return 格式化的上下文字符串
     */
    private String buildRagContext(PowerKnowledgeResult searchResult) {
        StringBuilder context = new StringBuilder();

        // 1. 添加来源文件列表
        if (searchResult.sources() != null && !searchResult.sources().isEmpty()) {
            context.append("📄 参考文档：\n");
            for (int i = 0; i < searchResult.sources().size(); i++) {
                PowerKnowledgeResult.SourceDocument source = searchResult.sources().get(i);
                context.append(String.format("[%d] %s\n", i + 1, source.filename()));
            }
            context.append("\n");
        }

        // 2. 添加文档内容片段（这是关键！）
        if (searchResult.rawContentSnippets() != null && !searchResult.rawContentSnippets().isEmpty()) {
            context.append("📄 参考文档内容：\n\n");
            for (int i = 0; i < searchResult.rawContentSnippets().size(); i++) {
                String snippet = searchResult.rawContentSnippets().get(i);
                context.append(String.format("--- 片段 %d ---\n%s\n\n", i + 1, snippet));
            }
        } else {
            context.append("（知识库中未找到相关内容）\n");
        }

        log.info("🔧 [RAG Context] 长度: {} 字符, 片段数: {}",
                context.length(),
                searchResult.rawContentSnippets() != null ? searchResult.rawContentSnippets().size() : 0);

        return context.toString();
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
