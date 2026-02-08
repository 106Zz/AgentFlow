package com.agenthub.api.agent_engine.model;

/**
 * 用户意图类型
 *
 * @author AgentHub
 * @since 2026-02-07
 */
public enum IntentType {
    /**
     * 知识库问答 - 需要检索文档证据
     */
    KB_QA,

    /**
     * 通用对话 - 闲聊、概念解释、讨论等
     */
    CHAT,

    /**
     * 未知意图
     */
    UNKNOWN
}
