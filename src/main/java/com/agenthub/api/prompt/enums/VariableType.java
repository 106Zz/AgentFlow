package com.agenthub.api.prompt.enums;

/**
 * 变量类型枚举
 * <p>定义提示词模板变量的数据类型</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public enum VariableType {

    /**
     * 字符串
     */
    STRING,

    /**
     * 数字
     */
    NUMBER,

    /**
     * 数组
     */
    ARRAY,

    /**
     * 对象
     */
    OBJECT,

    /**
     * 布尔值
     */
    BOOLEAN
}
