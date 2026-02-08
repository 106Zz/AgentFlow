package com.agenthub.api.ai.domain.llm;

/**
 * LLM 流式回调接口
 * <p>用于处理深度思考模型的流式输出</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
public interface StreamCallback {

    /**
     * 思考过程回调
     * <p>当模型返回思考内容时调用（reasoning_content）</p>
     */
    void onReasoning(String chunk);

    /**
     * 内容回调
     * <p>当模型返回最终回复时调用（content）</p>
     */
    void onContent(String chunk);

    /**
     * 工具调用回调
     * <p>当模型决定调用工具时触发</p>
     */
    default void onToolCall(java.util.List<com.agenthub.api.agent_engine.model.ToolCall> toolCalls) {}

    /**
     * 完成回调
     * <p>流式输出结束时调用</p>
     */
    default void onComplete() {}

    /**
     * 错误回调
     * <p>发生错误时调用</p>
     */
    default void onError(Throwable error) {}
}
