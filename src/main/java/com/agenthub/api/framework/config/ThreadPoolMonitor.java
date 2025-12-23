package com.agenthub.api.framework.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池监控
 * 定期输出线程池状态，便于调优
 */
@Component
public class ThreadPoolMonitor {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolMonitor.class);

    @Autowired
    @Qualifier("fileProcessExecutor")
    private ThreadPoolTaskExecutor fileProcessExecutor;

    @Autowired
    @Qualifier("vectorizeExecutor")
    private ThreadPoolTaskExecutor vectorizeExecutor;

    @Autowired
    @Qualifier("taskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    /**
     * 每5分钟输出一次线程池状态
     */
    @Scheduled(fixedRate = 300000)
    public void monitorThreadPool() {
        logThreadPoolStatus("文件处理线程池", fileProcessExecutor);
        logThreadPoolStatus("向量化线程池", vectorizeExecutor);
        logThreadPoolStatus("通用任务线程池", taskExecutor);
    }

    private void logThreadPoolStatus(String poolName, ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();
        
        log.info("【{}】状态 - 活跃线程:{}/{}, 队列任务:{}/{}, 已完成:{}, 总任务:{}",
                poolName,
                threadPool.getActiveCount(),
                threadPool.getPoolSize(),
                threadPool.getQueue().size(),
                executor.getQueueCapacity(),
                threadPool.getCompletedTaskCount(),
                threadPool.getTaskCount()
        );
    }
}
