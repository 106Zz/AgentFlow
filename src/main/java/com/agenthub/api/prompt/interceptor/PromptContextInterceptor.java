package com.agenthub.api.prompt.interceptor;

import com.agenthub.api.prompt.context.PromptContext;
import com.agenthub.api.prompt.context.PromptContextHolder;
import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.agenthub.api.prompt.enums.PromptType;
import com.agenthub.api.prompt.service.ISysPromptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词上下文拦截器
 *
 * <p>执行时机：
 * <ul>
 *   <li>请求前：从数据库加载提示词，存入 ThreadLocal</li>
 *   <li>请求后：清理 ThreadLocal</li>
 * </ul>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptContextInterceptor implements HandlerInterceptor {

    private final ISysPromptService promptService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 只处理 AI 相关请求
        if (!isAiRequest(request)) {
            return true;
        }

        try {
            // 构建提示词上下文
            PromptContext context = buildPromptContext();

            // 存入 ThreadLocal
            PromptContextHolder.setContext(context);

            log.debug("提示词上下文已加载，共 {} 条提示词", context.getPromptMap().size());

        } catch (Exception e) {
            log.warn("加载提示词上下文失败，使用空上下文: {}", e.getMessage());
            // 失败时使用空上下文，不影响主流程
            PromptContextHolder.setContext(PromptContext.empty());
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 清理 ThreadLocal，防止内存泄漏
        if (isAiRequest(request)) {
            PromptContextHolder.clear();
        }
    }

    /**
     * 判断是否为 AI 请求
     */
    private boolean isAiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.contains("/ai/") || uri.contains("/chat/");
    }

    /**
     * 构建提示词上下文
     */
    private PromptContext buildPromptContext() {
        // 加载各类型提示词
        List<SysPrompt> systemPrompts = promptService.listByType(PromptType.SYSTEM);
        List<SysPrompt> routerPrompts = promptService.listByType(PromptType.ROUTER);
        List<SysPrompt> workerPrompts = promptService.listByType(PromptType.WORKER);
        List<SysPrompt> skillPrompts = promptService.listByType(PromptType.SKILL);
        List<SysPrompt> toolPrompts = promptService.listByType(PromptType.TOOL);

        // 构建快速查找 Map
        Map<String, SysPrompt> promptMap = new HashMap<>();
        systemPrompts.forEach(p -> promptMap.put(p.getPromptCode(), p));
        routerPrompts.forEach(p -> promptMap.put(p.getPromptCode(), p));
        workerPrompts.forEach(p -> promptMap.put(p.getPromptCode(), p));
        skillPrompts.forEach(p -> promptMap.put(p.getPromptCode(), p));
        toolPrompts.forEach(p -> promptMap.put(p.getPromptCode(), p));

        return PromptContext.builder()
                .systemPrompts(systemPrompts)
                .routerPrompts(routerPrompts)
                .workerPrompts(workerPrompts)
                .skillPrompts(skillPrompts)
                .toolPrompts(toolPrompts)
                .promptMap(promptMap)
                .build();
    }
}
