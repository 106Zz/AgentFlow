package com.agenthub.api.prompt.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.agenthub.api.prompt.domain.entity.SysPromptCategory;
import com.agenthub.api.prompt.mapper.SysPromptCategoryMapper;
import com.agenthub.api.prompt.mapper.SysPromptMapper;
import com.agenthub.api.prompt.service.ISysPromptCategoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词分类 Service 实现
 *
 * @author AgentHub
 * @since 2026-01-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPromptCategoryServiceImpl extends ServiceImpl<SysPromptCategoryMapper, SysPromptCategory> implements ISysPromptCategoryService {

    private final SysPromptCategoryMapper sysPromptCategoryMapper;
    private final SysPromptMapper sysPromptMapper;

    @Override
    public List<SysPromptCategory> listTree() {
        // 查询所有分类
        LambdaQueryWrapper<SysPromptCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptCategory::getDelFlag, 0)
                .orderByAsc(SysPromptCategory::getSortOrder);
        List<SysPromptCategory> allCategories = sysPromptCategoryMapper.selectList(wrapper);

        // 构建树形结构
        return buildTree(allCategories, null);
    }

    @Override
    public List<SysPromptCategory> listByParentId(Long parentId) {
        LambdaQueryWrapper<SysPromptCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptCategory::getParentId, parentId)
                .eq(SysPromptCategory::getDelFlag, 0)
                .orderByAsc(SysPromptCategory::getSortOrder);
        return sysPromptCategoryMapper.selectList(wrapper);
    }

    @Override
    public SysPromptCategory getByCode(String categoryCode) {
        LambdaQueryWrapper<SysPromptCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptCategory::getCategoryCode, categoryCode)
                .eq(SysPromptCategory::getDelFlag, 0);
        return sysPromptCategoryMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysPromptCategory category) {
        // 检查代码是否重复
        if (StrUtil.isNotBlank(category.getCategoryCode())) {
            SysPromptCategory existing = getByCode(category.getCategoryCode());
            if (existing != null) {
                throw new IllegalArgumentException("分类代码已存在: " + category.getCategoryCode());
            }
        }

        // 验证父分类是否存在
        if (category.getParentId() != null && category.getParentId() != 0) {
            SysPromptCategory parent = this.getById(category.getParentId());
            if (parent == null || parent.getDelFlag() == 1) {
                throw new IllegalArgumentException("父分类不存在");
            }
        }

        boolean saved = this.save(category);
        if (!saved) {
            throw new RuntimeException("创建分类失败");
        }
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(SysPromptCategory category) {
        if (category.getId() == null) {
            throw new IllegalArgumentException("分类ID不能为空");
        }

        SysPromptCategory existing = this.getById(category.getId());
        if (existing == null || existing.getDelFlag() == 1) {
            throw new IllegalArgumentException("分类不存在");
        }

        // 检查代码是否与其他分类重复
        if (StrUtil.isNotBlank(category.getCategoryCode())) {
            LambdaQueryWrapper<SysPromptCategory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysPromptCategory::getCategoryCode, category.getCategoryCode())
                    .ne(SysPromptCategory::getId, category.getId())
                    .eq(SysPromptCategory::getDelFlag, 0);
            SysPromptCategory duplicate = sysPromptCategoryMapper.selectOne(wrapper);
            if (duplicate != null) {
                throw new IllegalArgumentException("分类代码已存在: " + category.getCategoryCode());
            }
        }

        // 验证父分类是否存在（且不能设置自己为父分类）
        if (category.getParentId() != null && category.getParentId() != 0) {
            if (category.getParentId().equals(category.getId())) {
                throw new IllegalArgumentException("不能将自己设置为父分类");
            }
            SysPromptCategory parent = this.getById(category.getParentId());
            if (parent == null || parent.getDelFlag() == 1) {
                throw new IllegalArgumentException("父分类不存在");
            }
        }

        return this.updateById(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Long id) {
        SysPromptCategory category = this.getById(id);
        if (category == null || category.getDelFlag() == 1) {
            return false;
        }

        // 检查是否有子分类
        LambdaQueryWrapper<SysPromptCategory> childWrapper = new LambdaQueryWrapper<>();
        childWrapper.eq(SysPromptCategory::getParentId, id)
                .eq(SysPromptCategory::getDelFlag, 0);
        Long childCount = sysPromptCategoryMapper.selectCount(childWrapper);
        if (childCount > 0) {
            throw new IllegalArgumentException("该分类下存在子分类，无法删除");
        }

        // 检查是否有关联的提示词
        LambdaQueryWrapper<SysPrompt> promptWrapper = new LambdaQueryWrapper<>();
        promptWrapper.eq(SysPrompt::getCategoryId, id)
                .eq(SysPrompt::getDelFlag, 0);
        Long promptCount = sysPromptMapper.selectCount(promptWrapper);
        if (promptCount > 0) {
            throw new IllegalArgumentException("该分类下存在提示词，无法删除");
        }

        return this.removeById(id);
    }

    /**
     * 构建树形结构
     *
     * @param allCategories 所有分类列表
     * @param parentId      父分类ID
     * @return 树形结构列表
     */
    private List<SysPromptCategory> buildTree(List<SysPromptCategory> allCategories, Long parentId) {
        List<SysPromptCategory> result = new ArrayList<>();

        for (SysPromptCategory category : allCategories) {
            // 判断是否为指定父节点的子节点
            boolean isChild;
            if (parentId == null) {
                // 根节点：parentId为null或0
                isChild = category.getParentId() == null || category.getParentId() == 0;
            } else {
                isChild = parentId.equals(category.getParentId());
            }

            if (isChild) {
                // 递归构建子节点
                List<SysPromptCategory> children = buildTree(allCategories, category.getId());
                category.setChildren(children);
                result.add(category);
            }
        }

        return result;
    }
}
