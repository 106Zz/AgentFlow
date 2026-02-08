package com.agenthub.api.agent_engine.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次执行结果
 * <p>用于 SinglePassExecutor 的执行返回</p>
 *
 * @param intent               识别的意图
 * @param intentConfidence     意图置信度
 * @param preRetrievalDone     是否完成预检索
 * @param finalAnswer          最终回答
 * @param thinkingContent       思考过程内容
 * @author AgentHub
 * @since 2026-02-07
 */
public record ExecutionResult(
        IntentType intent,
        double intentConfidence,
        boolean preRetrievalDone,
        String finalAnswer,
        String thinkingContent
) {

    /**
     * 创建带有最终回答的结果
     */
    public static ExecutionResult withAnswer(String answer) {
        return new ExecutionResult(null, 0.0, false, answer, "");
    }

    /**
     * 是否成功执行
     */
    public boolean isSuccess() {
        return finalAnswer != null && !finalAnswer.isEmpty();
    }

    /**
     * 获取用于展示的完整内容（包含思考过程）
     */
    public String getFullContent() {
        StringBuilder sb = new StringBuilder();
        if (thinkingContent != null && !thinkingContent.isEmpty()) {
            sb.append("```\n").append(thinkingContent).append("\n```\n\n");
        }
        if (finalAnswer != null) {
            sb.append(finalAnswer);
        }
        return sb.toString();
    }

    /**
     * 构建消息列表（用于保存到记忆）
     */
    public List<Message> toMessages(String userQuery) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(userQuery));

        StringBuilder assistantContent = new StringBuilder();
        if (thinkingContent != null && !thinkingContent.isEmpty()) {
            assistantContent.append("```\n").append(thinkingContent).append("\n```\n\n");
        }
        if (finalAnswer != null) {
            assistantContent.append(finalAnswer);
        }
        messages.add(new AssistantMessage(assistantContent.toString()));

        return messages;
    }
}
