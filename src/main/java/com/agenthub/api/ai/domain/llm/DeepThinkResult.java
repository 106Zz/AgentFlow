package com.agenthub.api.ai.domain.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 深度思考结果
 * <p>包含模型的思考过程和最终回复</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeepThinkResult {

    /**
     * 思考过程（reasoning_content）
     * <p>深度思考模型在回复前的思考过程</p>
     */
    private String reasoningContent;

    /**
     * 最终回复（content）
     * <p>模型思考完成后的正式回复</p>
     */
    private String content;

    /**
     * 使用的模型名称
     */
    private String model;

    /**
     * 是否有思考过程
     */
    public boolean hasReasoning() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }
}
