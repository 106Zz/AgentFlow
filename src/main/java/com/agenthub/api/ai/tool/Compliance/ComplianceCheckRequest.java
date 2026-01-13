package com.agenthub.api.ai.tool.Compliance;


import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("合规审查请求。当用户需要检查一段文本（如标书、合同、政策）是否符合电力行业规则时使用。")
public record ComplianceCheckRequest(
        @JsonPropertyDescription("待审查的文本内容，例如：'乙方偏差考核免责范围为5%'")
        @JsonProperty(required = true)
        String content,

        @JsonPropertyDescription("审查场景类型：BIDDING(标书), CONTRACT(合同), GENERAL(通用)")
        @JsonProperty(required = true, defaultValue = "GENERAL")
        String scene
) {
}
