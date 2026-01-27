package com.agenthub.api.framework.config;

import com.agenthub.api.prompt.interceptor.PromptContextInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 用于配置异步请求处理线程池、拦截器等
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    @Qualifier("taskExecutor")
    private final AsyncTaskExecutor taskExecutor;

    private final PromptContextInterceptor promptContextInterceptor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 设置默认的异步任务执行器，解决 SimpleAsyncTaskExecutor 警告
        configurer.setTaskExecutor(taskExecutor);
        // 设置超时时间（可选，例如 60秒）
        configurer.setDefaultTimeout(60000);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册提示词上下文拦截器
        registry.addInterceptor(promptContextInterceptor)
                .addPathPatterns("/ai/**", "/chat/**");
    }
}
