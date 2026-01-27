package com.agenthub.api.prompt.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.prompt.domain.entity.SysPromptTag;
import com.agenthub.api.prompt.domain.entity.SysPromptTagRelation;
import com.agenthub.api.prompt.mapper.SysPromptTagMapper;
import com.agenthub.api.prompt.mapper.SysPromptTagRelationMapper;
import com.agenthub.api.prompt.service.ISysPromptTagService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词标签 Service 实现
 *
 * @author AgentHub
 * @since 2026-01-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPromptTagServiceImpl extends ServiceImpl<SysPromptTagMapper, SysPromptTag> implements ISysPromptTagService {

    private final SysPromptTagMapper sysPromptTagMapper;
    private final SysPromptTagRelationMapper tagRelationMapper;

    @Override
    public List<SysPromptTag> listAll() {
        LambdaQueryWrapper<SysPromptTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptTag::getDelFlag, 0)
                .orderByAsc(SysPromptTag::getSortOrder);
        return sysPromptTagMapper.selectList(wrapper);
    }

    @Override
    public List<SysPromptTag> listByPromptId(Long promptId) {
        // 通过关联表查询标签
        LambdaQueryWrapper<SysPromptTagRelation> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.eq(SysPromptTagRelation::getPromptId, promptId);
        List<SysPromptTagRelation> relations = tagRelationMapper.selectList(relationWrapper);

        if (relations.isEmpty()) {
            return List.of();
        }

        // 获取标签ID列表
        List<Long> tagIds = relations.stream()
                .map(SysPromptTagRelation::getTagId)
                .collect(Collectors.toList());

        // 批量查询标签
        LambdaQueryWrapper<SysPromptTag> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(SysPromptTag::getId, tagIds)
                .eq(SysPromptTag::getDelFlag, 0)
                .orderByAsc(SysPromptTag::getSortOrder);
        return sysPromptTagMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysPromptTag tag) {
        // 检查标签名是否重复
        if (StrUtil.isNotBlank(tag.getTagName())) {
            LambdaQueryWrapper<SysPromptTag> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysPromptTag::getTagName, tag.getTagName())
                    .eq(SysPromptTag::getDelFlag, 0);
            SysPromptTag existing = sysPromptTagMapper.selectOne(wrapper);
            if (existing != null) {
                throw new IllegalArgumentException("标签名称已存在: " + tag.getTagName());
            }
        }

        boolean saved = this.save(tag);
        if (!saved) {
            throw new RuntimeException("创建标签失败");
        }
        return tag.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(SysPromptTag tag) {
        if (tag.getId() == null) {
            throw new IllegalArgumentException("标签ID不能为空");
        }

        SysPromptTag existing = this.getById(tag.getId());
        if (existing == null || existing.getDelFlag() == 1) {
            throw new IllegalArgumentException("标签不存在");
        }

        // 检查标签名是否与其他标签重复
        if (StrUtil.isNotBlank(tag.getTagName())) {
            LambdaQueryWrapper<SysPromptTag> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysPromptTag::getTagName, tag.getTagName())
                    .ne(SysPromptTag::getId, tag.getId())
                    .eq(SysPromptTag::getDelFlag, 0);
            SysPromptTag duplicate = sysPromptTagMapper.selectOne(wrapper);
            if (duplicate != null) {
                throw new IllegalArgumentException("标签名称已存在: " + tag.getTagName());
            }
        }

        return this.updateById(tag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Long id) {
        SysPromptTag tag = this.getById(id);
        if (tag == null || tag.getDelFlag() == 1) {
            return false;
        }

        // 删除标签关联关系
        LambdaQueryWrapper<SysPromptTagRelation> relationWrapper = new LambdaQueryWrapper<>();
        relationWrapper.eq(SysPromptTagRelation::getTagId, id);
        tagRelationMapper.delete(relationWrapper);

        // 删除标签
        return this.removeById(id);
    }
}
