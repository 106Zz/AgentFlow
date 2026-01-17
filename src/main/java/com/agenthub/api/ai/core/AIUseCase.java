package com.agenthub.api.ai.core;


/**
 * 策略模式接口
 */
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
