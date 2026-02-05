package com.agenthub.api.agent_engine.tool.impl;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.workflow.ComplianceReport;
import com.agenthub.api.ai.workflow.ComplianceWorkflowService;
import com.agenthub.api.prompt.builder.CaseSnapshotBuilder;
import com.agenthub.api.prompt.service.ICaseSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTool implements AgentTool {

    private final ComplianceWorkflowService workflowService;
    private final ICaseSnapshotService caseSnapshotService;
    private final ObjectMapper objectMapper;

    @Override
    public AgentToolDefinition getDefinition() {
        return AgentToolDefinition.builder()
                .name("audit_contract")
                .description("审查合同、标书或文档的合规性风险。当用户提供文档内容或要求审查合同时使用。")
                .parameterSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "doc_content": {
                                    "type": "string",
                                    "description": "文档内容。如果用户在对话中直接粘贴了文本，请填入此处。如果是上传的文件，该字段可留空，系统会自动读取上下文。"
                                }
                            }
                        }
                        """)
                .requiresConfirmation(true) // 合规审查通常比较重，建议确认
                .costWeight(10)
                .build();
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, AgentContext context) {
        long startTime = System.currentTimeMillis();
        
        // 1. 获取文档内容：优先取 LLM 传入的参数，其次取 Context 中的隐式文档
        String docContent = (String) request.getArguments().get("doc_content");
        if (docContent == null || docContent.isEmpty()) {
            docContent = context.getDocContent();
        }

        if (docContent == null || docContent.isEmpty()) {
            return ToolExecutionResult.failure("没有找到需要审查的文档内容。请先上传文档或粘贴文本。");
        }

        try {
            log.info("[AuditTool] 开始执行合规审查, userId: {}, docLength: {}", context.getUserId(), docContent.length());

            // 2. 调用原有的 Workflow
            // 注意：这里是同步等待，如果 Workflow 耗时很长，可能需要考虑改成异步返回 JobId
            // 但目前为了保持 V2 接口简单，先做同步调用
            ComplianceReport report = workflowService.executeAudit(docContent, context.getUserId()).join();

            // 3. 结果处理
            String outputJson = objectMapper.writeValueAsString(report);

            // 4. 异步冻结 Case (保持原有业务逻辑)
            freezeSuccessCase(context, report, startTime, docContent.length());

            return ToolExecutionResult.success(outputJson, report);

        } catch (Exception e) {
            log.error("AuditTool execution failed", e);
            freezeErrorCase(context, e, startTime);
            return ToolExecutionResult.failure("合规审查执行失败: " + e.getMessage());
        }
    }

    // --- 以下是 Case 冻结逻辑 (直接从 AuditUseCase 搬运并适配) ---

    private void freezeSuccessCase(AgentContext context, Object result, long startTime, int docLength) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("AUDIT")
                            .intent("AUDIT.TOOL_V2") // 区分来源
                            .input(context.getQuery(), context.getUserId() != null ? Long.valueOf(context.getUserId()) : null, context.getSessionId())
                            .capturePromptData()
                            .metadata("doc_length", String.valueOf(docLength))
                            .outputData(result != null ? result.toString() : "", duration, null)
                            .modelData("agent-v2", "deepseek-v3.2", null)
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[AuditTool] Case 冻结失败: {}", e.getMessage());
        }
    }

    private void freezeErrorCase(AgentContext context, Throwable error, long startTime) {
        try {
            long duration = System.currentTimeMillis() - startTime;
            caseSnapshotService.freezeAsync(
                    CaseSnapshotBuilder.create()
                            .scenario("AUDIT")
                            .intent("AUDIT.TOOL_V2_FAIL")
                            .input(context.getQuery(), context.getUserId() != null ? Long.valueOf(context.getUserId()) : null, context.getSessionId())
                            .capturePromptData()
                            .status("FAILED")
                            .errorMessage(error.getMessage())
                            .durationMs((int) duration)
                            .requestTime(LocalDateTime.now())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[AuditTool] Error Case 冻结失败: {}", e.getMessage());
        }
    }
}
