package com.agenthub.api.ai.usecase;


import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.ai.worker.CalculationWorker;
import com.agenthub.api.prompt.builder.CaseSnapshotBuilder;
import com.agenthub.api.prompt.service.ICaseSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Deprecated
@Slf4j
@Component
@RequiredArgsConstructor
public class CalcUseCase implements AIUseCase {

    private final CalculationWorker worker;
    private final ICaseSnapshotService caseSnapshotService;

    @Override
    public boolean support(String intent) {
        return "CALC".equals(intent);
    }

    @Override
    public AIResponse execute(AIRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            // 调用 Worker，并包装为 LIST 类型
            AIResponse response = AIResponse.ofList(
                    worker.executeReview(request.query())
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

            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("CALC")
                            .intent("CALC.DEVIATION")
                            .input(request.query(), request.userId() != null ? Long.valueOf(request.userId()) : null, request.sessionId())
                            .capturePromptData()
                            .outputData(result != null ? result.toString() : "", duration, null)
                            .modelData("dashscope", "deepseek-v3.2", null)
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[CalcUseCase] Case 冻结失败: {}", e.getMessage());
        }
    }

    private void freezeErrorCase(AIRequest request, Throwable error, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;

            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("CALC")
                            .intent("CALC.DEVIATION")
                            .input(request.query(), request.userId() != null ? Long.valueOf(request.userId()) : null, request.sessionId())
                            .capturePromptData()
                            .status("FAILED")
                            .errorMessage(error.getMessage())
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[CalcUseCase] Error Case 冻结失败: {}", e.getMessage());
        }
    }
}
