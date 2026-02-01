package com.agenthub.api.ai.core;


/**
 * 策略模式接口
 */
  /**
 * AI 用例接口 (Legacy)
 * @deprecated 已被 AgentTool 接口取代。
 * 请参考: com.agenthub.api.agent_engine.tool.AgentTool
 */
@Deprecated
public interface AIUseCase {

    /**
     * 我是否支持这个意图？
     */
    boolean support(String intent);

    /**
     * 执行业务逻辑
     */
    AIResponse execute(AIRequest request);
}
