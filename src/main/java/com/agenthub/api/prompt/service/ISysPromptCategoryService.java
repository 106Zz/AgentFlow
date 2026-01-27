package com.agenthub.api.prompt.service;

import com.agenthub.api.prompt.domain.entity.SysPromptCategory;

import java.util.List;

/**
 * 提示词分类 Service
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public interface ISysPromptCategoryService {

    /**
     * 查询所有分类（树形结构）
     *
     * @return 分类树
     */
    List<SysPromptCategory> listTree();

    /**
     * 根据父级 ID 查询子分类
     *
     * @param parentId 父级 ID
     * @return 子分类列表
     */
    List<SysPromptCategory> listByParentId(Long parentId);

    /**
     * 根据 code 查询分类
     *
     * @param categoryCode 分类代码
     * @return 分类实体
     */
    SysPromptCategory getByCode(String categoryCode);

    /**
     * 创建分类
     *
     * @param category 分类实体
     * @return 创建的分类 ID
     */
    Long create(SysPromptCategory category);

    /**
     * 更新分类
     *
     * @param category 分类实体
     * @return 是否成功
     */
    Boolean update(SysPromptCategory category);

    /**
     * 删除分类
     *
     * @param id 分类 ID
     * @return 是否成功
     */
    Boolean delete(Long id);
}
