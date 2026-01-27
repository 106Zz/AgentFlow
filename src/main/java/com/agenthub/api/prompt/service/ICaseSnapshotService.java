package com.agenthub.api.prompt.service;

import com.agenthub.api.prompt.domain.entity.CaseSnapshot;

/**
 * Case 快照服务
 * <p>负责冻结和查询 Case 快照</p>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public interface ICaseSnapshotService {

    /**
     * 冻结 Case（异步执行，不阻塞主流程）
     * <p>提示词数据应在调用前通过 CaseSnapshotBuilder.capturePromptData() 捕获</p>
     *
     * @param snapshot Case 快照
     */
    void freezeAsync(CaseSnapshot snapshot);

    /**
     * 根据 Case ID 查询快照
     *
     * @param caseId Case ID
     * @return Case 快照
     */
    CaseSnapshot getByCaseId(String caseId);

    /**
     * 标记 Case 为已评估
     *
     * @param caseId Case ID
     * @return 是否成功
     */
    Boolean markEvaluated(String caseId);
}
