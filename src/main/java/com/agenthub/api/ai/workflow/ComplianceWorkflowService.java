package com.agenthub.api.ai.workflow;

import com.agenthub.api.ai.domain.workflow.ComplianceReport;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import com.agenthub.api.ai.worker.CalculationWorker;
import com.agenthub.api.ai.worker.CommercialWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 全量合规工作流 (Workflow)
 * 职责: 像总包工头一样，协调各个 Worker (专工) 完成一次复杂的全身体检。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceWorkflowService {

    private final CommercialWorker commercialWorker;
    private final CalculationWorker calculationWorker;

    /**
     * 执行全量合规审查工作流
     * 模式：混合编排 (Hybrid Orchestration)
     * 1. 商务审查 (并行)
     * 2. 计算审查 (串行) - 但相对于商务审查，它是同时进行的
     * 3. 结果聚合
     */
    public CompletableFuture<ComplianceReport> executeAudit(String docContent, String userId) {
        log.info(">>>> [Workflow] 启动全量合规审查套餐...");

        // Step 1: 启动商务专工 (Parallel Worker)
        // CommercialWorker 内部已经是异步的 (Fan-Out)，这里我们拿到它的 Future
        CompletableFuture<List<WorkerResult>> commercialFuture =
                commercialWorker.executeCommercialAudit(docContent);

        // Step 2: 启动计算专工 (Serial Worker)
        // 计算逻辑通常依赖特定的数值提取，这里我们假设它也可以独立运行
        // 如果 calculation 依赖 commercial 的结果，这里需要用 thenCompose 串联
        // 但根据 TN-005，两者目前数据独立，因此我们选择 "总体并行" 以压缩时间
        CompletableFuture<List<WorkerResult>> calculationFuture =
                calculationWorker.executeReview(docContent);

        // Step 3: 聚合结果 (Join)
        return CompletableFuture.allOf(commercialFuture, calculationFuture)
                .thenApply(v -> {
                    List<WorkerResult> commercialResults = commercialFuture.join();
                    List<WorkerResult> calculationResults = calculationFuture.join();

                    // 合并结果集
                    List<WorkerResult> allResults = new ArrayList<>();
                    allResults.addAll(commercialResults);
                    allResults.addAll(calculationResults);

                    // 生成最终报告 (包含总耗时、风险等级汇总等)
                    return aggregateReport(allResults);
                });
    }

    private ComplianceReport aggregateReport(List<WorkerResult> results) {
        // 简单的聚合逻辑：只要有一个 false，整体就不合规
        boolean passed = results.stream().allMatch(WorkerResult::isPassed);
        int riskCount = (int) results.stream().filter(r -> !r.isPassed()).count();

        return new ComplianceReport(
                passed,
                riskCount,
                passed ? "合同完全合规" : "发现 " + riskCount + " 处合规风险，请注意核对",
                results
        );
    }

}
