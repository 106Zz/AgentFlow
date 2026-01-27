package com.agenthub.api.prompt.enums;

/**
 * 作用域枚举
 * <p>定义提示词的适用范围</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public enum Scope {

    /**
     * 全局
     * <p>所有用户、所有租户共享</p>
     */
    GLOBAL,

    /**
     * 租户级
     * <p>同一租户内的用户共享</p>
     */
    TENANT,

    /**
     * 用户级
     * <p>仅当前用户可用</p>
     */
    USER
}
