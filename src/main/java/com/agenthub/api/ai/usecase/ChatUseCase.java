package com.agenthub.api.ai.usecase;

import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeTool;
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
    
    // 注入底层 ChatModel，而不是配置死的 ChatClient，以便我们现场组装"满配版"Client
    private final ChatModel chatModel;
    
    // 注入记忆仓库，用于持久化对话历史
    private final ChatMemoryRepository chatMemoryRepository;

    // 注入原来的系统提示词 (角色设定)
    @Value("classpath:/prompts/rag-system-prompt.st")
    private Resource systemPromptResource;

    @Override
    public boolean support(String intent) {
        // CHAT 意图，或者意图为空时兜底
        return "CHAT".equals(intent) || intent == null || intent.isEmpty();
    }

    @Override
    public AIResponse execute(AIRequest request) {
        log.info(">>>> [UseCase] 进入通用 RAG 问答模式 (Stream): {}", request.query());
        
        // 1. 构建"满配版" ChatClient (带记忆、带人设)
        String conversationId = request.sessionId(); 
        ChatClient smartClient = buildSmartClient(conversationId);

        // 2. 检索 (Retrieve)
        PowerKnowledgeQuery searchArgs = new PowerKnowledgeQuery(request.query(), 3, null, null);
        PowerKnowledgeResult searchResult = knowledgeTool.retrieve(searchArgs);
        String context = searchResult.answer(); // 获取检索到的片段摘要

        // 3. 生成 (Generate - Stream)
        // 改为流式调用，返回 Flux<String>
        reactor.core.publisher.Flux<String> stream = smartClient.prompt()
                .system(s -> s.param("context", context)) 
                .user(request.query())
                .stream() // <--- 开启流式
                .content();

        // 4. 包装流式结果
        return AIResponse.ofStream(stream);
    }

    /**
     * 现场组装一个带记忆、带人设的 ChatClient
     */
    private ChatClient buildSmartClient(String conversationId) {
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPromptResource) // 找回系统提示词 (人设)
                // .defaultTools(knowledgeTool) // 如果需要 Function Call 可以加，但这里我们是手动调用的 RAG，所以不需要
                .defaultAdvisors(
                        // 找回记忆能力
                        MessageChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .chatMemoryRepository(chatMemoryRepository)
                                        .maxMessages(20) // 记住最近 20 条
                                        .build()
                        )
                        .conversationId(conversationId)  // 绑定会话ID
                        .build(),
                        
                        // 日志
                        new SimpleLoggerAdvisor()
                )
                .build();
    }
}
