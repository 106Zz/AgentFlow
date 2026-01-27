package com.agenthub.api.prompt.service;

import com.agenthub.api.prompt.domain.entity.SysPromptTag;

import java.util.List;

/**
 * 提示词标签 Service
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public interface ISysPromptTagService {

    /**
     * 查询所有标签
     *
     * @return 标签列表
     */
    List<SysPromptTag> listAll();

    /**
     * 根据提示词 ID 查询标签列表
     *
     * @param promptId 提示词 ID
     * @return 标签列表
     */
    List<SysPromptTag> listByPromptId(Long promptId);

    /**
     * 创建标签
     *
     * @param tag 标签实体
     * @return 创建的标签 ID
     */
    Long create(SysPromptTag tag);

    /**
     * 更新标签
     *
     * @param tag 标签实体
     * @return 是否成功
     */
    Boolean update(SysPromptTag tag);

    /**
     * 删除标签
     *
     * @param id 标签 ID
     * @return 是否成功
     */
    Boolean delete(Long id);
}
