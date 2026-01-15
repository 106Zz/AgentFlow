package com.agenthub.api.ai.service.impl;

import com.agenthub.api.ai.advisor.RerankerQuestionAnswerAdvisor;
import com.agenthub.api.ai.config.DashScopeRerankerConfig;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeTool;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.knowledge.domain.vo.StreamChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG聊天服务实现
 * 负责根据用户权限动态构建ChatClient
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagChatServiceImpl {

    private final ChatModel chatModel;
    private final ChatMemoryRepository chatMemoryRepository;


    private final PowerKnowledgeTool powerKnowledgeTool;


    @Value("classpath:/prompts/rag-system-prompt.st")
    private org.springframework.core.io.Resource systemPromptResource;

    /**
     * 根据当前用户权限创建ChatClient
     * 
     * @param sessionId 会话ID（用于对话记忆）
     * @return 配置好的ChatClient
     */
    public ChatClient createUserChatClient(String sessionId) {
        // 获取当前用户信息
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        log.debug("创建ChatClient - 用户ID: {}, 是否管理员: {}, 会话ID: {}", userId, isAdmin, sessionId);
        
        // 构建过滤表达式
        String filterExpression = buildFilterExpression(userId, isAdmin);
        
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPromptResource)
                .defaultTools(powerKnowledgeTool)
                .defaultAdvisors(
                        // 对话记忆（基于sessionId）
                        MessageChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .chatMemoryRepository(chatMemoryRepository)
                                        .maxMessages(20)
                                        .build()
                        )
                        .conversationId(sessionId)  // 使用sessionId作为对话ID
                        .build(),
                        
                        // 日志输出
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    /**
     * 构建用户权限过滤表达式
     */
    private String buildFilterExpression(Long userId, boolean isAdmin) {
        // 修正：管理员在聊天时也应遵循隐私隔离（只能看公共 + 自己上传的）
        // 防止管理员无意中检索到其他用户的私有文档
        String filter = String.format(
            "(user_id == 0 && is_public == '1') || user_id == %d",
            userId
        );
        log.debug("用户过滤表达式: {}", filter);
        return filter;
    }

    /**
     * 发送消息并获取回复
     * 
     * @param sessionId 会话ID
     * @param question 用户问题
     * @return AI回复
     */
    public String chat(String sessionId, String question) {
        ChatClient client = createUserChatClient(sessionId);
        
        return client.prompt()
                .user(question)
                .call()
                .content();
    }


    /**
     * 流式回复
     */
    public Flux<StreamChunk> chatStream(String sessionId, String question) {
        ChatClient client = createUserChatClient(sessionId);
        
        // 用于原子更新 Token 使用情况
        AtomicReference<Usage> usageRef = new AtomicReference<>();

        return client.prompt()
                .user(question)
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    // 1. 捕获 Token 使用情况 (通常在最后一个包或每个包都有)
                    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                        usageRef.set(response.getMetadata().getUsage());
                    }
                })
                .map(resp -> {
                    // 2. 尝试从 Metadata 提取思考过程 (适配 DeepSeek)
                    String reasoningPart = null;
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        Map<String, Object> meta = resp.getResult().getOutput().getMetadata();
                        if (meta != null) {
                            Object t = meta.get("reasoning_content");
                            if (t == null) t = meta.get("reasoning");
                            
                            if (t != null && !t.toString().isEmpty()) {
                                reasoningPart = t.toString();
                            }
                        }
                    }

                    // 3. 提取正文回答
                    String answerPart = "";
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        answerPart = resp.getResult().getOutput().getText();
                    }

                    return new StreamChunk(reasoningPart, answerPart,sessionId);
                })
                .doOnComplete(() -> {
                    // 4. 流结束时记录 Token 消耗
                    org.springframework.ai.chat.metadata.Usage usage = usageRef.get();
                    if (usage != null) {
                        long outputTokens = usage.getTotalTokens() - usage.getPromptTokens();
                        log.info("会话:{} 流式对话完成 Token消耗[输入:{}, 输出:{}, 总计:{}]",
                                sessionId, usage.getPromptTokens(), outputTokens, usage.getTotalTokens());
                    }
                })
                .doOnError(error -> {
                    // 5. 异常记录
                    log.error("会话:{} 流式对话出错", sessionId, error);
                })
                .onErrorResume(e -> {
                    // 6. 优雅降级：向前端发送错误提示，而不是直接中断流
                    return Flux.just(new StreamChunk(null, "\n\n> [系统提示] 生成过程中发生错误: " + e.getMessage(), sessionId));
                });
    }
}
