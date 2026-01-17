package com.agenthub.api.ai.worker;

import com.agenthub.api.ai.service.skill.CommercialSkills;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import com.agenthub.api.ai.domain.workflow.WorkerResult.WorkerType;
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
public class CommercialWorker {

    private final CommercialSkills commercialSkills;
    private final Executor executor;

    public CommercialWorker(CommercialSkills commercialSkills,
                            @Qualifier("agentWorkerExecutor") Executor agentWorkerExecutor) {
        this.commercialSkills = commercialSkills;
        this.executor = agentWorkerExecutor;
    }


    /**
     * 并行执行商务审查
     */
    public CompletableFuture<List<WorkerResult>> executeCommercialAudit(String contractContent) {
        log.info(">>>> [Worker] 开始并行审核商务条款...");

        List<String> auditItems = List.of("投标保证金", "履约保证金", "付款周期", "价格条款合法性");

        // 扇出 (Fan-Out)
        List<CompletableFuture<WorkerResult>> futures = auditItems.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> {
                            // ✅ 现在这里直接返回 WorkerResult，类型完美匹配！
                            return commercialSkills.auditCommercialTerm(item, contractContent);
                        }, executor)
                        .exceptionally(ex -> {
                            // 异常兜底
                            log.error("Skill 执行失败: {}", ex.getMessage());
                            return new WorkerResult(
                                    item,
                                    WorkerType.COMMERCIAL,
                                    false,
                                    "AI服务暂时不可用: " + ex.getMessage(),
                                    "请稍后重试",
                                    List.of()
                            );
                        }))
                .toList();

        // 扇入 (Fan-In)
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }


}
