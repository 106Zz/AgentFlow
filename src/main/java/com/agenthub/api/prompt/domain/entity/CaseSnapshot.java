package com.agenthub.api.prompt.domain.entity;


import com.agenthub.api.common.base.BaseEntity;
import com.agenthub.api.prompt.enums.CaseStatus;
import com.agenthub.api.prompt.enums.Scenario;
import com.agenthub.api.common.handler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Case 快照实体
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("case_snapshot")
public class CaseSnapshot extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Case ID
     * 格式：case_YYYYMMDD_随机8位
     */
    private String caseId;

    /**
     * 场景类型
     */
    private Scenario scenario;

    /**
     * 具体意图
     */
    private String intent;

    /**
     * 输入数据
     * {
     *   "query": "用户问题",
     *   "user_id": 10001,
     *   "session_id": "sess_xxx",
     *   "conversation_history": [...]
     * }
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode inputData;

    /**
     * 上下文数据
     * {
     *   "intent": "CHAT.RAG_QUERY",
     *   "rag_result": {...},
     *   "rerank_result": [...]
     * }
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode contextData;

    /**
     * 提示词数据
     * {
     *   "system_prompt": {...},
     *   "skill_prompts": [...],
     *   "rendered_prompt": "...",
     *   "variables": {...}
     * }
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode promptData;

    /**
     * 模型配置
     * {
     *   "provider": "dashscope",
     *   "name": "deepseek-v3.2",
     *   "parameters": {...}
     * }
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode modelData;

    /**
     * 输出数据（流式结束后填充）
     * {
     *   "raw_response": "...",
     *   "reasoning_content": null,
     *   "response_time_ms": 1234,
     *   "token_usage": {...},
     *   "finish_reason": "stop"
     * }
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode outputData;

    /**
     * 元数据
     * {
     *   "environment": "production",
     *   "server_hostname": "agenthub-01",
     *   "spring_version": "3.2.12",
     *   "agenthub_version": "4.0.0"
     * }
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode metadata;

    /**
     * 状态
     */
    private CaseStatus status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 请求时间
     */
    private LocalDateTime requestTime;

    /**
     * 响应时间
     */
    private LocalDateTime responseTime;

    /**
     * 耗时（毫秒）
     */
    private Integer durationMs;

    /**
     * 是否已评估
     */
    private Boolean isEvaluated;

    /**
     * Rule Judge 结果
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode ruleJudgeResult;

    /**
     * AI Judge 结果
     */
    @TableField(typeHandler = JsonNodeTypeHandler.class)
    private JsonNode aiJudgeResult;
}
