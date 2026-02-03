package com.agenthub.api.agent_engine.core.thinking;

import com.agenthub.api.ai.domain.llm.StreamCallback;
import com.alibaba.dashscope.common.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 流式深度思考器
 * <p>封装二次思考的流式输出逻辑，作为 Agent 流程的最终输出阶段</p>
 *
 * <p>输出格式：</p>
 * <pre>
 * @@THINK_START@@
 * [思考内容流式输出...]
 * @@THINK_END@@
 * [最终回答流式输出...]
 * </pre>
 */
@Slf4j
public class StreamDeepThinker {

    private final DashScopeNativeServiceWrapper nativeService;
    private final ChatMemoryRepository chatMemoryRepository;

    public StreamDeepThinker(DashScopeNativeServiceWrapper nativeService,
                             ChatMemoryRepository chatMemoryRepository) {
        this.nativeService = nativeService;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /**
     * 执行流式深度思考
     *
     * @param model    模型名称
     * @param messages 消息列表
     * @param sessionId 会话ID
     * @param userQuery 用户原始查询
     * @return 流式响应
     */
    public Flux<String> think(String model, List<Message> messages, String sessionId, String userQuery) {
        return Flux.create(sink -> {
            StringBuffer fullContent = new StringBuffer();
            StringBuffer fullReasoning = new StringBuffer();
            final boolean[] state = new boolean[]{false, false}; // [0]=思考已开始, [1]=思考已结束

            nativeService.deepThinkStream(model, messages, new StreamCallback() {
                @Override
                public void onReasoning(String reasoning) {
                    if (!state[0]) {
                        sink.next("@@THINK_START@@");
                        state[0] = true;
                    }
                    fullReasoning.append(reasoning);
                    sink.next(reasoning);
                }

                @Override
                public void onContent(String content) {
                    if (state[0] && !state[1]) {
                        sink.next("@@THINK_END@@");
                        state[1] = true;
                    }
                    fullContent.append(content);
                    sink.next(content);
                }

                @Override
                public void onComplete() {
                    if (state[0] && !state[1]) {
                        sink.next("@@THINK_END@@");
                    }

                    // 构建完整回答用于持久化
                    String finalAnswer = buildFinalAnswer(fullReasoning, fullContent);
                    saveMemory(sessionId, userQuery, finalAnswer);
                    sink.complete();
                }

                @Override
                public void onError(Throwable e) {
                    log.error("[StreamDeepThinker] Error", e);
                    sink.error(e);
                }
            });
        });
    }

    private String buildFinalAnswer(StringBuffer reasoning, StringBuffer content) {
        String result = "";
        if (reasoning.length() > 0) {
            result += "```\n" + reasoning + "\n```\n\n";
        }
        result += content.toString();
        return result;
    }

    private void saveMemory(String sessionId, String query, String reply) {
        try {
            List<org.springframework.ai.chat.messages.Message> current =
                chatMemoryRepository.findByConversationId(sessionId);
            if (current == null) {
                current = new java.util.ArrayList<>();
            }
            current.add(new UserMessage(query));
            current.add(new AssistantMessage(reply));
            chatMemoryRepository.saveAll(sessionId, current);
        } catch (Exception e) {
            log.error("[StreamDeepThinker] Memory save failed", e);
        }
    }

    /**
     * DashScope 原生服务包装接口
     * <p>用于解耦，方便测试和替换</p>
     */
    @FunctionalInterface
    public interface DashScopeNativeServiceWrapper {
        void deepThinkStream(String model, List<Message> messages, StreamCallback callback);
    }
}
