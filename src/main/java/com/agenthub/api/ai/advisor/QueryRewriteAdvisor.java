package com.agenthub.api.ai.advisor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class QueryRewriteAdvisor implements CallAdvisor,StreamAdvisor{

    private final ChatModel chatModel;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientRequest rewrittenRequest = rewriteQuery(chatClientRequest);
        return callAdvisorChain.nextCall(rewrittenRequest);

    }

    // ========== 流式调用 ==========
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest rewrittenRequest = rewriteQuery(request);
        return chain.nextStream(rewrittenRequest);
    }

    // ========== 核心改写逻辑（复用） ==========
    private ChatClientRequest rewriteQuery(ChatClientRequest request) {
        Prompt originalPrompt = request.prompt();
        List<Message> messages = originalPrompt.getInstructions();

        // 找到用户的最后一条消息
        String originalQuery = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage) {
                originalQuery = msg.getText();
                break;
            }
        }

        if (originalQuery == null || originalQuery.isBlank()) {
            return request;
        }

        log.info("【查询改写】原始问题: {}", originalQuery);

        // 用 LLM 改写问题
        String rewritePrompt = """
                请将以下用户问题改写成更适合知识库检索的查询语句。
                要求：
                1. 提取关键信息和关键词
                2. 补充必要的上下文
                3. 使用更专业的术语
                4. 只返回改写后的问题，不要解释
                
                原始问题：%s
                
                改写后的问题：
                """.formatted(originalQuery);

        String rewrittenQuery = chatModel.call(rewritePrompt).trim();

        log.info("【查询改写】改写后问题: {}", rewrittenQuery);

        // 替换 Prompt 中的用户消息
        List<Message> newMessages = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof UserMessage && msg.getText().equals(originalQuery)) {
                newMessages.add(new UserMessage(rewrittenQuery));
            } else {
                newMessages.add(msg);
            }
        }

        // 创建新的 Prompt 和 Request
        Prompt newPrompt = new Prompt(newMessages, originalPrompt.getOptions());

        Map<String, Object> context = request.context();
        context.put("original_query", originalQuery);
        context.put("rewritten_query", rewrittenQuery);

        return ChatClientRequest.builder()
                .prompt(newPrompt)
                .context(context)
                .build();
    }

    @Override
    public String getName() {
        return "QueryRewriteAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

}
