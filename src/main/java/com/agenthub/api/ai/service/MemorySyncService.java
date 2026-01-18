package com.agenthub.api.ai.service;

import com.agenthub.api.ai.domain.workflow.ComplianceReport;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆同步服务
 * 负责将业务执行结果（Audit/Calc）转化为自然语言摘要并存入对话记忆
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySyncService {

    private final ChatMemoryRepository chatMemoryRepository;

    /**
     * 异步同步结果到记忆 (核心入口)
     * 使用 @Async("taskExecutor") 确保不阻塞主请求
     */
    @Async("taskExecutor")
    public void sync(String sessionId, String userQuery, Object result) {
        try {
            // 1. 领域驱动摘要 (Domain-Driven Summarization)
            String summary = generateDomainSummary(result);
            if (summary == null || summary.isEmpty()) return;

            log.info(">>>> [Memory] 同步业务数据: sessionId={}, summaryLen={}", sessionId, summary.length());

            // 2. 构造消息对 (模拟一轮对话历史)
            List<Message> memoryBatch = List.of(
                new UserMessage(userQuery),
                new AssistantMessage(summary)
            );

            // 3. 持久化 (追加模式)
            // 获取当前会话历史
            List<Message> history = chatMemoryRepository.findByConversationId(sessionId);
            history.addAll(memoryBatch);
            
            // 滑动窗口控制 (只保留最近20条，防止上下文过大)
            if (history.size() > 20) {
                history = history.subList(history.size() - 20, history.size());
            }
            
            chatMemoryRepository.saveAll(sessionId, history);
            
        } catch (Exception e) {
            log.error("记忆同步异常: sessionId={}", sessionId, e);
        }
    }

    /**
     * 核心：根据业务类型生成深度摘要
     */
    private String generateDomainSummary(Object result) {
        // 场景 A: 合规审查报告 (AuditUseCase 返回)
        if (result instanceof ComplianceReport report) {
            return formatComplianceReport(report);
        }
        
        // 场景 B: 工人执行结果列表 (CalcUseCase 可能返回 List<WorkerResult>)
        if (result instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof WorkerResult) {
            @SuppressWarnings("unchecked")
            List<WorkerResult> results = (List<WorkerResult>) list;
            return formatWorkerResults(results);
        }
        
        // 场景 C: 普通文本
        if (result instanceof String str) {
            return str;
        }

        return null;
    }

    /**
     * 格式化审查报告
     */
    private String formatComplianceReport(ComplianceReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统业务通知】我已完成合规审查。");
        sb.append("总体结论：").append(report.overallPassed() ? "通过" : "存在风险").append("。");
        sb.append("共发现 ").append(report.riskCount()).append(" 个风险项。\n");

        // 提取风险详情 (只取前 5 个，防止 Token 溢出)
        if (!report.overallPassed() && report.details() != null) {
            String risks = report.details().stream()
                    .filter(r -> !r.isPassed())
                    .limit(5)
                    .map(r -> String.format("- [%s]: %s (建议: %s)", r.item(), r.riskDetails(), r.suggestion()))
                    .collect(Collectors.joining("\n"));
            sb.append("风险详情如下：\n").append(risks);
        }
        return sb.toString();
    }

    /**
     * 格式化计算/原子结果
     */
    private String formatWorkerResults(List<WorkerResult> results) {
        return "【系统业务通知】计算/检查结果如下：\n" + results.stream()
                .limit(5)
                .map(r -> String.format("- %s: %s (结论: %s)", r.item(), r.riskDetails(), r.isPassed() ? "OK" : "Issue"))
                .collect(Collectors.joining("\n"));
    }
}