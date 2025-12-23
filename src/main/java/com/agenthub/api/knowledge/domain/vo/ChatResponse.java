package com.agenthub.api.knowledge.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 聊天响应对象
 */
@Data
public class ChatResponse {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * AI回答
     */
    private String answer;

    /**
     * 引用的知识来源
     */
    private List<KnowledgeSource> sources;

    /**
     * 响应时间（毫秒）
     */
    private Long responseTime;

    /**
     * 知识来源
     */
    @Data
    public static class KnowledgeSource {
        /**
         * 知识ID
         */
        private Long knowledgeId;

        /**
         * 知识标题
         */
        private String title;

        /**
         * 相关内容片段
         */
        private String content;

        /**
         * 相似度分数
         */
        private Double score;

        /**
         * 文件类型
         */
        private String fileType;
    }
}
