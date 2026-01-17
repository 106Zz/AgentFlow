package com.agenthub.api.ai.domain.calculator;


public record DeviationInput(
        double actualLoad,   // 实际用电量
        double planLoad,     // 计划用电量/申报电量
        double marketPrice   // 结算均价
) {

}
