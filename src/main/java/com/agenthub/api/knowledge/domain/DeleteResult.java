package com.agenthub.api.knowledge.domain;

import java.util.List;

/**
 * 知识库删除结果对象
 *
 * <p>用于封装批量删除操作的执行结果</p>
 */
public class DeleteResult {

    /** 成功删除的数量 */
    private final int successCount;

    /** 失败的知识库ID列表 */
    private final List<Long> failedKnowledgeIds;

    /** 错误信息列表 */
    private final List<String> errors;

    public DeleteResult(int successCount, List<Long> failedKnowledgeIds, List<String> errors) {
        this.successCount = successCount;
        this.failedKnowledgeIds = failedKnowledgeIds;
        this.errors = errors;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public List<Long> getFailedKnowledgeIds() {
        return failedKnowledgeIds;
    }

    public List<String> getErrors() {
        return errors;
    }

    /**
     * 是否全部删除成功
     */
    public boolean isAllSuccess() {
        return failedKnowledgeIds == null || failedKnowledgeIds.isEmpty();
    }
}
