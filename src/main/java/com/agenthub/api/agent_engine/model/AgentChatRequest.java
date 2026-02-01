package com.agenthub.api.agent_engine.model;

import lombok.Data;

/**
 * V2 Agent 聊天接口请求参数
 */
@Data
public class AgentChatRequest {
    
    /**
     * 用户提问内容
     */
    private String query;
    
    /**
     * 用户 ID
     */
    private String userId;
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 文档内容 (可选)
     * 用于合规审查等场景，前端解析后的文本内容
     */
    private String docContent;
}
