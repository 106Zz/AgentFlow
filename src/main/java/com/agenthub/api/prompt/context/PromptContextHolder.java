package com.agenthub.api.prompt.context;

/**
 * 提示词上下文持有者
 * <p>使用 ThreadLocal 存储当前线程的提示词上下文</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public class PromptContextHolder {

    private static final ThreadLocal<PromptContext> CONTEXT = new ThreadLocal<>();

    /**
     * 设置上下文（拦截器调用）
     */
    public static void setContext(PromptContext context) {
        CONTEXT.set(context);
    }

    /**
     * 获取上下文
     */
    public static PromptContext getContext() {
        PromptContext context = CONTEXT.get();
        if (context == null) {
            context = PromptContext.empty();
            CONTEXT.set(context);
        }
        return context;
    }

    /**
     * 清理上下文（请求结束时调用）
     */
    public static void clear() {
        CONTEXT.remove();
    }

    // ========== 便捷方法 ==========

    /**
     * 获取提示词
     */
    public static String get(String code) {
        return getContext().getPromptContent(code);
    }

    /**
     * 获取系统提示词
     */
    public static String getSystem(String code) {
        return getContext().getPromptContent(code);
    }

    /**
     * 获取 Router 提示词
     */
    public static String getRouter(String code) {
        return getContext().getPromptContent(code);
    }

    /**
     * 获取 Worker 提示词
     */
    public static String getWorker(String code) {
        return getContext().getPromptContent(code);
    }

    /**
     * 获取 Skill 提示词
     */
    public static String getSkill(String code) {
        return getContext().getPromptContent(code);
    }

    /**
     * 获取 Tool 提示词
     */
    public static String getTool(String code) {
        return getContext().getPromptContent(code);
    }
}
