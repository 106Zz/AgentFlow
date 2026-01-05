package com.agenthub.api.framework.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 用于配置异步请求处理线程池等
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    @Qualifier("taskExecutor")
    private AsyncTaskExecutor taskExecutor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 设置默认的异步任务执行器，解决 SimpleAsyncTaskExecutor 警告
        configurer.setTaskExecutor(taskExecutor);
        // 设置超时时间（可选，例如 60秒）
        configurer.setDefaultTimeout(60000);
    }
}
