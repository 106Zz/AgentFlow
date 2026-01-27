package com.agenthub.api.prompt.enums;

/**
 * 模板类型枚举
 * <p>定义提示词模板的渲染引擎类型</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public enum TemplateType {

    /**
     * FreeMarker 模板
     * <p>支持复杂的模板逻辑、变量替换</p>
     * <p>示例：${userName}、<#list items as item></p>
     */
    FREEMARKER,

    /**
     * Spring EL 表达式
     * <p>使用 SpEL 进行表达式计算</p>
     * <p>示例：#{user.name}</p>
     */
    SPEL,

    /**
     * 纯文本
     * <p>不进行任何模板渲染，直接使用原始文本</p>
     */
    TEXT
}
