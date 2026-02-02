package com.agenthub.api.agent_engine.model;

import lombok.Builder;
import lombok.Data;

/**
 * 评估结果模型
 * <p>用于承载 Judge (自省/审计) 环节的输出结果</p>
 *
 * @author AgentHub
 * @since 2026-02-02
 */
@Data
@Builder
public class EvaluationResult {
    
    /**
     * 是否通过审计
     */
    private boolean passed;
    
    /**
     * 原因 (如果失败) 或 说明 (如果通过)
     */
    private String reason;
    
    /**
     * 修正建议 (Judge 给出的改进文本)
     */
    private String suggestion;

    /**
     * 快速构建通过结果
     */
    public static EvaluationResult pass(String msg) {
        return EvaluationResult.builder().passed(true).reason(msg).build();
    }

    /**
     * 快速构建失败结果
     */
    public static EvaluationResult fail(String reason) {
        return EvaluationResult.builder().passed(false).reason(reason).build();
    }
}
