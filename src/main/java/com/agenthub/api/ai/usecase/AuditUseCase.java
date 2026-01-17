package com.agenthub.api.ai.usecase;


import com.agenthub.api.ai.core.AIRequest;
import com.agenthub.api.ai.core.AIResponse;
import com.agenthub.api.ai.core.AIUseCase;
import com.agenthub.api.ai.workflow.ComplianceWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditUseCase implements AIUseCase {

    private final ComplianceWorkflowService workflow;

    @Override
    public boolean support(String intent) {
        return "AUDIT".equals(intent);
    }

    @Override
    public AIResponse execute(AIRequest request) {
        if (request.docContent() == null || request.docContent().isEmpty()) {
            return AIResponse.ofText("请先上传需要审查的合同或标书文档。");
        }
        // 调用 Workflow，并包装为 REPORT 类型
        return AIResponse.ofReport(
                workflow.executeAudit(request.docContent(), request.userId())
        );
    }
}
