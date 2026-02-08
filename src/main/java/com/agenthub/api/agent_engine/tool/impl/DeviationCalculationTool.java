package com.agenthub.api.agent_engine.tool.impl;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.AgentToolDefinition;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import com.agenthub.api.ai.worker.CalculationWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviationCalculationTool implements AgentTool {

    private final CalculationWorker calculationWorker;
    private final ObjectMapper objectMapper;

    @Override
    public AgentToolDefinition getDefinition() {
        return AgentToolDefinition.builder()
                .name("calculate_deviation")
                .description("计算偏差考核费用和免责情况。当用户询问「考核多少钱」、「免责了吗」或提供「计划xxx实际xxx」的数据时使用。")
                .parameterSchema("""
                        {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "The full user query containing power data (e.g., 'Plan 500, Actual 480')."
                                }
                            },
                            "required": ["query"]
                        }
                        """)
                .requiresConfirmation(false)
                .costWeight(5)
                .build();
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, AgentContext context) {
        String query = (String) request.getArguments().get("query");
        if (query == null) {
            return ToolExecutionResult.failure("Missing required argument: query");
        }

        try {
            log.info("[AgentTool] Invoking calculate_deviation with query: {}", query);
            
            // 调用旧的 Worker，它是异步的
            CompletableFuture<List<WorkerResult>> future = calculationWorker.executeReview(query);
            
            // 同步等待结果 (设置30秒超时防止死锁)
            List<WorkerResult> results = future.get(30, TimeUnit.SECONDS);
            
            // 序列化结果供 LLM 阅读
            String outputJson = objectMapper.writeValueAsString(results);
            
            return ToolExecutionResult.success(outputJson, results);
            
        } catch (Exception e) {
            log.error("Error executing calculate_deviation tool", e);
            return ToolExecutionResult.failure("Execution failed: " + e.getMessage());
        }
    }
}