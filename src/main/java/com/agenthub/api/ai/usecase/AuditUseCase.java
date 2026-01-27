package com.agenthub.api.ai.usecase;


import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.ai.workflow.ComplianceWorkflowService;
import com.agenthub.api.prompt.builder.CaseSnapshotBuilder;
import com.agenthub.api.prompt.service.ICaseSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditUseCase implements AIUseCase {

    private final ComplianceWorkflowService workflow;
    private final ICaseSnapshotService caseSnapshotService;

    @Override
    public boolean support(String intent) {
        return "AUDIT".equals(intent);
    }

    @Override
    public AIResponse execute(AIRequest request) {
        long startTime = System.currentTimeMillis();

        if (request.docContent() == null || request.docContent().isEmpty()) {
            return AIResponse.ofText("请先上传需要审查的合同或标书文档。");
        }

        try {
            // 调用 Workflow，并包装为 REPORT 类型
            AIResponse response = AIResponse.ofReport(
                    workflow.executeAudit(request.docContent(), request.userId())
            );

            // 异步冻结成功的 Case
            final long finalStartTime = startTime;
            response.getAsyncData().whenComplete((result, error) -> {
                if (error == null) {
                    freezeSuccessCase(request, result, finalStartTime);
                } else {
                    freezeErrorCase(request, error, finalStartTime);
                }
            });

            return response;

        } catch (Exception e) {
            freezeErrorCase(request, e, startTime);
            throw e;
        }
    }

    private void freezeSuccessCase(AIRequest request, Object result, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            int docLength = request.docContent() != null ? request.docContent().length() : 0;

            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("AUDIT")
                            .intent("AUDIT.COMMERCE")
                            .input(request.query(), request.userId() != null ? Long.valueOf(request.userId()) : null, request.sessionId())
                            .capturePromptData()
                            .metadata("doc_length", String.valueOf(docLength))
                            .outputData(result != null ? result.toString() : "", duration, null)
                            .modelData("dashscope", "deepseek-v3.2", null)
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[AuditUseCase] Case 冻结失败: {}", e.getMessage());
        }
    }

    private void freezeErrorCase(AIRequest request, Throwable error, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;

            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("AUDIT")
                            .intent("AUDIT.COMMERCE")
                            .input(request.query(), request.userId() != null ? Long.valueOf(request.userId()) : null, request.sessionId())
                            .capturePromptData()
                            .status("FAILED")
                            .errorMessage(error.getMessage())
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[AuditUseCase] Error Case 冻结失败: {}", e.getMessage());
        }
    }
}
