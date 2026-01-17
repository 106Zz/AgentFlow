package com.agenthub.api.ai.worker;

import com.agenthub.api.ai.service.skill.CommercialSkills;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommercialWorker {

    private final CommercialSkills commercialSkills;

    // 精准注入我们刚才定义的线程池
    @Qualifier("agentWorkerExecutor")
    private final Executor executor;


    public List<CommercialAuditResult> executeCommercialAudit(String contractContent) {
        log.info(">>>> [Worker] 开始并行审核商务条款...");

        // 1. 定义审查清单 (确定性骨架)
        List<String> auditItems = List.of("投标保证金", "履约保证金", "付款周期", "价格条款合法性");

        // 2. 提交异步任务
        List<CompletableFuture<CommercialAuditResult>> futures = auditItems.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> {
                            // 异步执行 Skill
                            return commercialSkills.auditCommercialTerm(item, contractContent);
                        }, executor) // 指定线程池，不占用主线程
                        .exceptionally(ex -> {
                            // 3. 异常兜底 (Resilience)
                            // 如果某一项查失败了（比如超时），返回一个默认的"未知"结果，而不是抛出异常中断流程
                            log.error("审查项 [{}] 失败: {}", item, ex.getMessage());
                            return new CommercialAuditResult(
                                    item,
                                    false,
                                    "审查服务暂时不可用: " + ex.getMessage(),
                                    "无",
                                    List.of()
                            );
                        }))
                .toList();

        // 4. 阻塞等待所有结果汇聚 (Join)
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }


}
