package com.agenthub.api.framework.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "fileProcessExecutor")
    public ThreadPoolTaskExecutor fileProcessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("FileProcess-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        
        log.info("文件处理线程池初始化完成");
        return executor;
    }

    @Bean(name = "vectorizeExecutor")
    public ThreadPoolTaskExecutor vectorizeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(processors);
        executor.setMaxPoolSize(processors * 2);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("Vectorize-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        
        log.info("向量化处理线程池初始化完成");
        return executor;
    }

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("Async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        
        log.info("通用异步任务线程池初始化完成");
        return executor;
    }

    /**
     * 新增：Agent 专用工作线程池
     * 场景：专门用于 CommercialWorker 并行调用 Skill，以及等待 LLM 响应
     * 特点：IO 密集型任务，核心线程数可以设大一点，因为它们大部分时间在"等待"而不是"计算"
     */
    @Bean(name="agentWorkerExecutor")
    public ThreadPoolTaskExecutor agentWorkerExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：设置为 10 或 20。
        // 因为 AI 响应慢，我们需要更多的线程同时挂起等待，而不是像 CPU 任务那样限制在核数附近
        executor.setCorePoolSize(10);

        // 最大线程数：突发流量时的上限
        executor.setMaxPoolSize(40);

        // 队列容量：如果一下子来了 200 个合同，先在队列里排队
        executor.setQueueCapacity(200);

        // 线程名前缀：方便在日志里看到 [AgentWorker-1] 报错，而不是 [Async-1]
        executor.setThreadNamePrefix("AgentWorker-");

        // 拒绝策略：如果队列满了，由调用者（主线程）自己跑，保证不丢单
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 优雅关闭：停机时等待任务做完
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("Agent工作线程池初始化完成");
        return executor;
    }

    @Bean("hybridSearchExecutor")
    public Executor hybridSearchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);        // 核心线程数
        executor.setMaxPoolSize(4);         // 最大线程数
        executor.setQueueCapacity(10);      // 队列容量
        executor.setThreadNamePrefix("hybrid-search-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("【异步配置】混合检索线程池初始化完成");
        return executor;
    }

    /**
     * 异步异常处理器
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            log.error("【异步异常】方法: {}, 参数: {}, 异常: {}",
                    method.getName(), params, throwable.getMessage(), throwable);
        };
    }

}
