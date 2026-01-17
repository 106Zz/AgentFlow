package com.agenthub.api.ai.domain.knowledge;

import com.fasterxml.jackson.annotation.*;

@JsonClassDescription("电力行业知识库查询请求")
public record PowerKnowledgeQuery(
        @JsonPropertyDescription("具体的查询问题或关键词，例如：'2026年广东电力交易规模是多少'。")
        @JsonProperty(required = true)
        String query,

        @JsonPropertyDescription("需要返回的相关文档片段数量（默认为5）。")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer topK,

        @JsonPropertyDescription("可选的年份过滤器（例如 '2025', '2026'）。")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String yearFilter,


        @JsonPropertyDescription("文档类别过滤，可选值: TECHNICAL(技术规范), BUSINESS(商务/价格), LEGAL(政策法规)。")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String category

) {
        // 4. 新增：容错构造器
        // 当模型“犯傻”只传一个字符串（而不是 JSON 对象）时，Jackson 会调这个方法
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PowerKnowledgeQuery fromString(String query) {
                // 更新：新增字段默认传 null，保证旧版本调用兼容性
                return new PowerKnowledgeQuery(query, null, null, null);
        }
}
