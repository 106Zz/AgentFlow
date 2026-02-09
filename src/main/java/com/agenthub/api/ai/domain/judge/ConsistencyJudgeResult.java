package com.agenthub.api.ai.domain.judge;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 内容一致性审计结果
 *
 * @author AgentHub
 * @since 2026-02-09
 */
@JsonClassDescription("内容一致性审计结果")
public record ConsistencyJudgeResult(
    @JsonPropertyDescription("判断结果：PASS 或 FAIL: 原因")
    String result,

    @JsonPropertyDescription("是否执行成功")
    boolean success
) {}