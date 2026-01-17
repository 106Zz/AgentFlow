package com.agenthub.api.ai.service.skill;


import com.agenthub.api.ai.domain.calculator.DeviationInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 合规审查技能服务 (Stateless Skills)
 * 职责：封装"Prompt策略" + "工具绑定"，实现业务逻辑的原子化复用。
 */
@Service
@Slf4j
public class ComplianceSkills {

    private final ChatClient chatClient;

    public ComplianceSkills(ChatClient.Builder builder) {
        // 使用 builder 构建专用的 client，可以在这里设置默认的 system prompt
        this.chatClient = builder.build();
    }

    /**
     * 技能：从非结构化文本中提取偏差考核参数
     * 输入示例："计划500MW，实际480，价格0.45元"
     * 输出：DeviationInput[planLoad=500.0, actualLoad=480.0, marketPrice=0.45]
     */
    public DeviationInput extractDeviationParams(String userQuery) {
        return chatClient.prompt()
                .system(s -> s.text("""
                    你是一个电力交易数据提取助手。你的任务是从用户的自然语言描述中提取计算偏差考核所需的参数。
                    
                    请提取以下字段：
                    1. planLoad (计划电量/负荷): 单位统一转换为 MWh (兆瓦时)。如果用户说"10万千瓦"，请转换为 100 MW。
                    2. actualLoad (实际电量/负荷): 单位统一转换为 MWh。
                    3. marketPrice (市场价格): 单位统一转换为 元/MWh。如果用户说"3毛5"或"0.35元/千瓦时"，请转换为 350 元/MWh。
                    
                    注意：如果用户未提及某项数据，且无法推断，请填 -1 并在后续流程处理。
                    """))
                .user(userQuery)
                .call()
                // 核心：Spring AI 会自动解析 JSON 并映射为 Record 对象
                .entity(DeviationInput.class);
    }

    /**
     * 技能升级：从用户自然语言中提取目标年份
     * 输入: "帮我看看去年(2025)的..." -> 输出: "2025"
     * 输入: "计算一下..." (没说年份) -> 输出: "2026" (默认值)
     */
    public String extractYearContext(String userQuery) {
        return chatClient.prompt()
                .system("你是一个时间意图识别器。请从用户的输入中提取年份信息。" +
                        "如果用户未提及年份，默认返回当前年份 '2026'。" +
                        "仅返回年份数字，不要任何标点符号。")
                .user(userQuery)
                .call()
                .content(); // e.g., "2025"
    }

}
