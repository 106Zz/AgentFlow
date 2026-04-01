package com.agenthub.api.agent_engine.tool.impl;

import com.agenthub.api.agent_engine.model.AgentContext;
import com.agenthub.api.agent_engine.model.ToolExecutionRequest;
import com.agenthub.api.agent_engine.model.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsistencyJudgeToolTest {

    private ConsistencyJudgeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new ConsistencyJudgeTool(objectMapper);
    }

    @Test
    void testToolDefinition() {
        var def = tool.getDefinition();
        assertEquals("consistency_judge", def.getName());
        assertTrue(def.getDescription().contains("内容一致性审计"));
    }

    @Test
    void testPassWhenEvidenceIsEmptyAndAnswerSaysNotFound() {
        Map<String, Object> args = new HashMap<>();
        args.put("user_query", "什么是XXX");
        args.put("agent_answer", "抱歉，我暂时没有找到相关信息");
        args.put("evidence", "[]");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .toolName("consistency_judge")
                .arguments(args)
                .build();

        AgentContext context = AgentContext.builder().build();
        ToolExecutionResult result = tool.execute(request, context);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().startsWith("PASS"));
    }

    @Test
    void testFailWhenClaimsEvidenceButEvidenceIsEmpty() {
        Map<String, Object> args = new HashMap<>();
        args.put("user_query", "2025年价格是多少");
        args.put("agent_answer", "根据文档显示，2025年的价格是0.5元");
        args.put("evidence", "[]");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .toolName("consistency_judge")
                .arguments(args)
                .build();

        AgentContext context = AgentContext.builder().build();
        ToolExecutionResult result = tool.execute(request, context);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().startsWith("FAIL"));
        assertTrue(result.getOutput().contains("但证据为空"));
    }

    @Test
    void testFailWhenContainsFactualClaimsButEvidenceIsEmpty() {
        Map<String, Object> args = new HashMap<>();
        args.put("user_query", "2026年价格");
        args.put("agent_answer", "2026年的价格是0.5元");
        args.put("evidence", "[]");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .toolName("consistency_judge")
                .arguments(args)
                .build();

        AgentContext context = AgentContext.builder().build();
        ToolExecutionResult result = tool.execute(request, context);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().startsWith("FAIL"));
    }

    @Test
    void testPassWhenEvidenceExists() {
        Map<String, Object> args = new HashMap<>();
        args.put("user_query", "2025年价格");
        args.put("agent_answer", "根据文档显示，2025年的价格是0.5元");
        args.put("evidence", "[\"2025年电力市场价格为0.5元\"]");

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .toolName("consistency_judge")
                .arguments(args)
                .build();

        AgentContext context = AgentContext.builder().build();
        ToolExecutionResult result = tool.execute(request, context);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().startsWith("PASS"));
    }
}