package com.agenthub.api.prompt.context;

import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 提示词上下文
 * <p>一次请求期间所有提示词的「容器」</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
@Data
@Builder
public class PromptContext {

    /**
     * 系统提示词（RAG系统提示等）
     */
    private List<SysPrompt> systemPrompts;

    /**
     * Router 提示词
     */
    private List<SysPrompt> routerPrompts;

    /**
     * Worker 提示词
     */
    private List<SysPrompt> workerPrompts;

    /**
     * Skill 提示词（参数提取）
     */
    private List<SysPrompt> skillPrompts;

    /**
     * Tool 提示词
     */
    private List<SysPrompt> toolPrompts;

    /**
     * 所有提示词的 Map（code → Prompt）
     * 方便快速查找
     */
    private Map<String, SysPrompt> promptMap;

    /**
     * 根据代码获取提示词
     */
    public SysPrompt getPrompt(String code) {
        return promptMap != null ? promptMap.get(code) : null;
    }

    /**
     * 获取提示词内容（模板字符串）
     */
    public String getPromptContent(String code) {
        SysPrompt prompt = getPrompt(code);
        if (prompt == null || prompt.getContent() == null) {
            return null;
        }
        return extractTemplate(prompt.getContent());
    }

    /**
     * 从 JsonNode 中提取 template 字段
     * 如果 content 是纯文本，直接返回；如果是 JSON，提取 template 字段
     */
    private String extractTemplate(JsonNode content) {
        if (content == null) {
            return null;
        }
        if (content.isObject() && content.has("template")) {
            return content.get("template").asText();
        }
        return content.asText();
    }

    /**
     * 空上下文（降级时使用）
     */
    public static PromptContext empty() {
        return PromptContext.builder()
                .systemPrompts(List.of())
                .routerPrompts(List.of())
                .workerPrompts(List.of())
                .skillPrompts(List.of())
                .toolPrompts(List.of())
                .promptMap(Map.of())
                .build();
    }
}
