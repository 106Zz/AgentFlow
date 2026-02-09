package com.agenthub.api.agent_engine.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
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

    // 文档上下文 (用于合规审查等需要处理文件的场景)
    private String docContent;

    // ========== v2.0 新增：意图识别相关 ==========

    /**
     * 意图类型 (意图识别后填充)
     */
    private IntentType intent;

    /**
     * 预检索内容 (KB_QA 意图时填充，包含 EvidenceBlock)
     */
    private String preRetrievedContent;

    /**
     * 意图置信度
     */
    private Double intentConfidence;

    /**
     * 是否已完成预检索
     * <p>用于避免在 LLM 阶段重复调用 knowledge_search</p>
     */
    private boolean preRetrievalDone;

    // ========== v2.1 新增：工具调用记录 ==========

    /**
     * 工具调用记录列表
     * <p>记录本次对话中所有工具调用及其结果，用于 Judge 审计</p>
     */
    private List<ToolCallRecord> toolCallRecords;

    /**
     * 添加工具调用记录
     *
     * @param record 工具调用记录
     */
    public void addToolCallRecord(ToolCallRecord record) {
        if (this.toolCallRecords == null) {
            this.toolCallRecords = new ArrayList<>();
        }
        this.toolCallRecords.add(record);
    }

    /**
     * 是否有工具调用记录
     */
    public boolean hasToolCallRecords() {
        return this.toolCallRecords != null && !this.toolCallRecords.isEmpty();
    }

    // ========== v2.2 新增：来源文件列表 ==========

    /**
     * 来源文件列表 (用于前端渲染下载链接)
     * <p>知识库检索后填充，包含文件名和 OSS 下载链接</p>
     */
    private List<SourceDocument> sources;

    /**
     * 来源文件记录（简化版，用于 SSE 传输）
     */
    @Data
    @Builder
    public static class SourceDocument {
        private String filename;
        private String downloadUrl;
    }

    // 扩展字段，用于存储临时的会话状态或用户画像标签
    private Map<String, Object> attributes;
}