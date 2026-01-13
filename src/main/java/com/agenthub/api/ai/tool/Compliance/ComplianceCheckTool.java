package com.agenthub.api.ai.tool.Compliance;


import com.agenthub.api.ai.service.ComplianceService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ComplianceCheckTool {

    private final ComplianceService complianceService;

    // 这里的 description 非常重要！它决定了 AI 什么时候会调用这个工具
    @Tool(description = "合规性审查工具。当用户要求'检查标书'、'审核合同条款'、'判断是否合规'或'查找风险'时，必须调用此工具。")
    public ComplianceCheckResult check(ComplianceCheckRequest request) {
        // 只是简单的转发给 Service，保持 Tool 层的轻薄
        return complianceService.audit(request);
    }

}
