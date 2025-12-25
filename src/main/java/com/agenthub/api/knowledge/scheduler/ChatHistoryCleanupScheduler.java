package com.agenthub.api.knowledge.scheduler;


import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 聊天历史清理任务
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryCleanupScheduler {

    private final IChatHistoryService chatHistoryService;

    /**
     * 每天凌晨 3 点清理 6 个月前的聊天记录
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 每天 03:00:00 执行
    public void cleanOldChatHistory() {
        LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);

        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(ChatHistory::getCreateTime, sixMonthsAgo);

        long deletedCount = chatHistoryService.remove(wrapper) ?
                chatHistoryService.count(wrapper) : 0;  // MyBatis-Plus 的 remove 返回 boolean，这里可以用其他方式统计

        // 更准确的统计方式：先查数量再删
        long count = chatHistoryService.count(wrapper);
        if (count > 0) {
            boolean success = chatHistoryService.remove(wrapper);
            log.info("聊天历史清理任务执行：删除 {} 个月前记录 {} 条，成功：{}", 6, count, success);
        } else {
            log.info("聊天历史清理任务执行：无需要删除的旧记录");
        }
    }
}
