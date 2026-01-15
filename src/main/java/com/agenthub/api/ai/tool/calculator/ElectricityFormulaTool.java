package com.agenthub.api.ai.tool.calculator;

import com.agenthub.api.ai.service.ElectricityFormulaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElectricityFormulaTool {

    private final ElectricityFormulaService formulaService;


    @Tool(description = """
        【电力结算公式引擎】
        用于精确计算电力市场的偏差考核费用。
        仅在用户明确提供（或文本中包含）'实际电量'、'计划电量'和'价格'时调用。
        严禁用于普通的数学加减乘除。
        """)
    public DeviationResult calculate(DeviationInput input, double threshold) {
        return formulaService.calculateDeviation(input, threshold);
    }

}
