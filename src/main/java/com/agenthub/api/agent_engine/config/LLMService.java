package com.agenthub.api.agent_engine.config;

import com.agenthub.api.agent_engine.tool.AgentTool;
import com.agenthub.api.ai.domain.llm.DeepThinkResult;
import com.agenthub.api.ai.domain.llm.StreamCallback;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 统一 LLM 服务接口
 * <p>屏蔽底层 SDK 差异（DashScope / Ollama），支持运行时通过配置切换</p>
 *
 * <p>切换方式: {@code app.llm.provider=ollama|dashscope}</p>
 *
 * @author AgentHub
 * @since 2026-04-25
 */
public interface LLMService {

    /**
     * 非流式调用 (简单提示词)
     *
     * @param model  模型名称
     * @param prompt 用户提示
     * @param system 系统提示（可选，null 则使用默认）
     * @return 深度思考结果
     */
    DeepThinkResult deepThink(String model, String prompt, String system);

    /**
     * 非流式调用 (多轮对话)
     *
     * @param model    模型名称
     * @param messages Spring AI 消息列表
     * @return 深度思考结果
     */
    DeepThinkResult deepThink(String model, List<Message> messages);

    /**
     * 流式调用 (无工具)
     *
     * @param model    模型名称
     * @param messages Spring AI 消息列表
     * @param callback 流式回调
     */
    void deepThinkStream(String model, List<Message> messages, StreamCallback callback);

    /**
     * 流式调用 (带工具)
     *
     * @param model    模型名称
     * @param messages Spring AI 消息列表
     * @param tools    业务层工具列表
     * @param callback 流式回调
     */
    void deepThinkStream(String model, List<Message> messages,
                         List<AgentTool> tools, StreamCallback callback);
}
