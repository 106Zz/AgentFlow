package com.agenthub.api.agent_engine.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Agent 运行上下文
 * 包含当前对话的所有环境信息，用于 CapabilityResolver 进行裁决
 */
@Data
@Builder
public class AgentContext {
    private String sessionId;
    private String userId;
    private String query;
    private String tenantId;
    
    // 新增：文档上下文 (用于合规审查等需要处理文件的场景)
    private String docContent;
    
    // 扩展字段，用于存储临时的会话状态或用户画像标签
    private Map<String, Object> attributes;
}