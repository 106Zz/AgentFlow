package com.agenthub.api.ai.tool.knowledge;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import com.agenthub.api.ai.service.PowerKnowledgeService;

@Slf4j
@Component // 注册为 Spring Bean
@RequiredArgsConstructor
public class PowerKnowledgeTool {

    private final PowerKnowledgeService powerKnowledgeService;

    /**
     * 注意：方法名 retrieve 并不重要，重要的是 @Tool 注解
     * description 直接决定了 AI 调用的精准度
     */
    @Tool(description = """
        【必须调用】广东电力市场专属知识库工具（覆盖2025-2026年最新交易规则、现货结算公式、技术规范）。
        
        调用原则：
        1. 任何涉及“电力”、“能源”、“交易”、“结算”、“考核”、“电价”的问题，必须优先调用此工具。
        2. 即使你觉得自己知道答案，也必须调用此工具以获取最新（2026版）文件依据。
        3. 严禁使用你训练数据中的过时知识回答电力相关问题。
        4. 如果用户只是进行日常闲聊（如“你好”、“你是谁”），请勿调用此工具。
        """)
    public PowerKnowledgeResult retrieve(PowerKnowledgeQuery query) {
        // 这里只是做一个简单的转发，保持逻辑分层
        // Tool 层负责“对接 AI”，Service 层负责“业务逻辑”
        return powerKnowledgeService.retrieve(query);
    }
}
