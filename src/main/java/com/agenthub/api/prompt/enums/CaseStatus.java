package com.agenthub.api.prompt.enums;

/**
 * Case 状态枚举
 * <p>定义 Case 快照的处理状态</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public enum CaseStatus {

    /**
     * 等待中
     * <p>流式响应进行中</p>
     */
    PENDING,

    /**
     * 已完成
     */
    COMPLETED,

    /**
     * 失败
     */
    FAILED
}
