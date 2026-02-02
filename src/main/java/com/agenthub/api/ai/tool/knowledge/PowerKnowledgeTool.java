package com.agenthub.api.ai.tool.knowledge;


import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import com.agenthub.api.ai.service.PowerKnowledgeService;

@Deprecated
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
                【必须调用】广东电力市场专属知识库工具。
                参数说明：
                       - query: 查询关键词。
                       - category: [可选] 业务分类过滤。
                       - 'BUSINESS': 涉及电价、结算公式、费用、补偿、合约。
                       - 'TECHNICAL': 涉及技术标准、物理参数、接入规范、负荷曲线、新能源消纳。
                        - 'REGULATION': 涉及交易规则、管理办法、信用评价、政策通知。
            """)
    public PowerKnowledgeResult retrieve(PowerKnowledgeQuery query) {
        // 这里只是做一个简单的转发，保持逻辑分层
        // Tool 层负责“对接 AI”，Service 层负责“业务逻辑”
        return powerKnowledgeService.retrieve(query);
    }
}
