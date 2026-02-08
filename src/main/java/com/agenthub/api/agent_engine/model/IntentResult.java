package com.agenthub.api.agent_engine.model;

/**
 * 意图识别结果
 *
 * @param intent       意图类型
 * @param confidence   置信度 (0.0-1.0)
 * @param reasoning    识别理由
 * @param needsPreRetrieval 是否需要预检索
 * @author AgentHub
 * @since 2026-02-07
 */
public record IntentResult(
        IntentType intent,
        double confidence,
        String reasoning,
        boolean needsPreRetrieval
) {

    /**
     * 判断是否为高置信度
     */
    public boolean isHighConfidence() {
        return confidence >= 0.7;
    }

    // ============== 静态工厂方法 ==============

    public static IntentResult kbQa(double confidence, String reasoning) {
        return new IntentResult(IntentType.KB_QA, confidence, reasoning, confidence >= 0.7);
    }

    public static IntentResult chat(double confidence, String reasoning) {
        return new IntentResult(IntentType.CHAT, confidence, reasoning, false);
    }

    public static IntentResult unknown(String reasoning) {
        return new IntentResult(IntentType.UNKNOWN, 0.0, reasoning, false);
    }
}
