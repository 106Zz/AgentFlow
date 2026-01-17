package com.agenthub.api.ai.usecase;


import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.ai.worker.CalculationWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CalcUseCase implements AIUseCase {

    private final CalculationWorker worker;

    @Override
    public boolean support(String intent) {
        return "CALC".equals(intent);
    }

    @Override
    public AIResponse execute(AIRequest request) {
        // 调用 Worker，并包装为 LIST 类型
        return AIResponse.ofList(
                worker.executeReview(request.query())
        );
    }
}
