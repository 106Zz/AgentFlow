package com.agenthub.api.prompt.enums;

/**
 * 变更类型枚举
 * <p>定义提示词版本变更的类型</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public enum ChangeType {

    /**
     * 创建
     * <p>新建提示词时的初始版本</p>
     */
    CREATE,

    /**
     * 更新
     * <p>修改提示词内容</p>
     */
    UPDATE,

    /**
     * 回滚
     * <p>回滚到历史版本</p>
     */
    ROLLBACK
}
