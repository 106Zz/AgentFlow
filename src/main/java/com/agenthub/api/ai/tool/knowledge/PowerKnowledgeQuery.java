package com.agenthub.api.ai.tool.knowledge;

import com.fasterxml.jackson.annotation.*;

import java.util.List;

@JsonClassDescription("电力行业知识库查询请求")
public record PowerKnowledgeQuery(
        @JsonPropertyDescription("具体的查询问题或关键词，例如：'2026年广东电力交易规模是多少'。")
        @JsonProperty(required = true) // 3. 强调这个是必须的
        String query,

        @JsonPropertyDescription("需要返回的相关文档片段数量（默认为3）。如果问题需要广泛的信息，可以设置为5或更高。")
        @JsonInclude(JsonInclude.Include.NON_NULL) // 允许为空，使用默认值
        Integer topK,

        @JsonPropertyDescription("可选的年份过滤器（例如 '2025', '2026'）。如果用户问题包含特定年份，请提取年份填入此处以提高搜索准确性。")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String yearFilter

) {
        // 4. 新增：容错构造器
        // 当模型“犯傻”只传一个字符串（而不是 JSON 对象）时，Jackson 会调这个方法
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PowerKnowledgeQuery fromString(String query) {
                // 这里的 query 就是模型传回来的 "2026年 偏差考核..."
                // 我们把它手动塞进 query 字段，其他字段给 null
                return new PowerKnowledgeQuery(query, null, null);
        }
}
