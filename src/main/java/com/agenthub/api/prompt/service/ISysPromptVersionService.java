package com.agenthub.api.prompt.service;

import com.agenthub.api.prompt.domain.entity.SysPromptVersion;

import java.util.List;

/**
 * 提示词版本 Service
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public interface ISysPromptVersionService {

    /**
     * 根据提示词 ID 查询版本历史
     *
     * @param promptId 提示词 ID
     * @return 版本列表
     */
    List<SysPromptVersion> listByPromptId(Long promptId);

    /**
     * 根据提示词代码查询最新版本
     *
     * @param promptCode 提示词代码
     * @return 最新版本
     */
    SysPromptVersion getLatestByCode(String promptCode);

    /**
     * 根据版本号查询
     *
     * @param promptId 提示词 ID
     * @param version   版本号
     * @return 版本实体
     */
    SysPromptVersion getByVersion(Long promptId, String version);

    /**
     * 创建版本快照
     *
     * @param version    版本实体
     * @return 创建的版本 ID
     */
    Long create(SysPromptVersion version);

    /**
     * 删除版本
     *
     * @param id 版本 ID
     * @return 是否成功
     */
    Boolean delete(Long id);
}
