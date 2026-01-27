package com.agenthub.api.prompt.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.agenthub.api.prompt.domain.entity.SysPromptCategory;
import com.agenthub.api.prompt.domain.entity.SysPromptTag;
import com.agenthub.api.prompt.domain.entity.SysPromptTagRelation;
import com.agenthub.api.prompt.domain.entity.SysPromptVersion;
import com.agenthub.api.prompt.domain.dto.request.PromptCreateRequest;
import com.agenthub.api.prompt.domain.dto.request.PromptQueryRequest;
import com.agenthub.api.prompt.domain.dto.request.PromptUpdateRequest;
import com.agenthub.api.prompt.domain.vo.PromptVO;
import com.agenthub.api.prompt.enums.ChangeType;
import com.agenthub.api.prompt.enums.PromptType;
import com.agenthub.api.prompt.enums.Scope;
import com.agenthub.api.prompt.enums.TemplateType;
import com.agenthub.api.prompt.mapper.SysPromptCategoryMapper;
import com.agenthub.api.prompt.mapper.SysPromptMapper;
import com.agenthub.api.prompt.mapper.SysPromptTagRelationMapper;
import com.agenthub.api.prompt.mapper.SysPromptVersionMapper;
import com.agenthub.api.prompt.service.ISysPromptService;
import com.agenthub.api.prompt.service.ISysPromptTagService;
import com.agenthub.api.prompt.service.ISysPromptVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词管理 Service 实现
 *
 * @author AgentHub
 * @since 2026-01-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPromptServiceImpl extends ServiceImpl<SysPromptMapper, SysPrompt> implements ISysPromptService {

    private final SysPromptMapper sysPromptMapper;
    private final SysPromptTagRelationMapper tagRelationMapper;
    private final SysPromptCategoryMapper categoryMapper;
    private final SysPromptVersionMapper versionMapper;
    private final ISysPromptTagService tagService;
    private final ISysPromptVersionService versionService;

    @Override
    public PageResult<PromptVO> selectPage(PromptQueryRequest request) {
        LambdaQueryWrapper<SysPrompt> wrapper = new LambdaQueryWrapper<>();

        // 提示词代码模糊查询
        wrapper.like(StrUtil.isNotBlank(request.getPromptCode()), SysPrompt::getPromptCode, request.getPromptCode());
        // 提示词名称模糊查询
        wrapper.like(StrUtil.isNotBlank(request.getPromptName()), SysPrompt::getPromptName, request.getPromptName());
        // 提示词类型精确查询
        if (StrUtil.isNotBlank(request.getPromptType())) {
            try {
                wrapper.eq(SysPrompt::getPromptType, PromptType.valueOf(request.getPromptType()));
            } catch (IllegalArgumentException e) {
                log.warn("无效的提示词类型: {}", request.getPromptType());
            }
        }
        // 分类查询
        wrapper.eq(request.getCategoryId() != null, SysPrompt::getCategoryId, request.getCategoryId());
        // 激活状态查询
        wrapper.eq(request.getIsActive() != null, SysPrompt::getIsActive, request.getIsActive());
        // 租户查询
        wrapper.eq(request.getTenantId() != null, SysPrompt::getTenantId, request.getTenantId());
        // 作用域查询
        wrapper.eq(StrUtil.isNotBlank(request.getScope()), SysPrompt::getScope,
                Scope.valueOf(request.getScope()));

        wrapper.eq(SysPrompt::getDelFlag, 0);
        wrapper.orderByDesc(SysPrompt::getPriority).orderByDesc(SysPrompt::getCreateTime);

        IPage<SysPrompt> page = this.page(request.build(), wrapper);
        // 手动构建 PageResult，需要将 SysPrompt 转换为 PromptVO
        PageResult<PromptVO> result = new PageResult<>();
        result.setRows(buildVOList(page.getRecords()));
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        return result;
    }

    @Override
    public List<SysPrompt> listByType(PromptType promptType) {
        LambdaQueryWrapper<SysPrompt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPrompt::getPromptType, promptType)
                .eq(SysPrompt::getIsActive, true)
                .eq(SysPrompt::getDelFlag, 0)
                .orderByDesc(SysPrompt::getPriority)
                .orderByDesc(SysPrompt::getCreateTime);
        return sysPromptMapper.selectList(wrapper);
    }

    @Override
    public SysPrompt getByCode(String promptCode) {
        LambdaQueryWrapper<SysPrompt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPrompt::getPromptCode, promptCode)
                .eq(SysPrompt::getDelFlag, 0);
        return sysPromptMapper.selectOne(wrapper);
    }

    @Override
    public PromptVO getDetail(Long id) {
        SysPrompt prompt = this.getById(id);
        if (prompt == null || prompt.getDelFlag() == 1) {
            return null;
        }

        PromptVO vo = buildVO(prompt);

        // 填充分类信息
        if (prompt.getCategoryId() != null) {
            SysPromptCategory category = categoryMapper.selectById(prompt.getCategoryId());
            if (category != null) {
                vo.setCategoryName(category.getCategoryName());
            }
        }

        // 填充标签信息
        List<SysPromptTag> tags = tagService.listByPromptId(id);
        List<PromptVO.TagVO> tagVOList = tags.stream()
                .map(tag -> PromptVO.TagVO.builder()
                        .id(tag.getId())
                        .tagName(tag.getTagName())
                        .tagColor(tag.getTagColor())
                        .build())
                .collect(Collectors.toList());
        vo.setTags(tagVOList);

        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(PromptCreateRequest request) {
        // 检查代码是否重复
        SysPrompt existing = getByCode(request.getPromptCode());
        if (existing != null) {
            throw new IllegalArgumentException("提示词代码已存在: " + request.getPromptCode());
        }

        SysPrompt prompt = new SysPrompt();
        BeanUtils.copyProperties(request, prompt);

        // 设置枚举类型
        prompt.setPromptType(PromptType.valueOf(request.getPromptType()));
        prompt.setTemplateType(TemplateType.valueOf(request.getTemplateType()));
        prompt.setScope(Scope.valueOf(request.getScope()));
        prompt.setIsActive(true);
        prompt.setIsLocked(false);

        boolean saved = this.save(prompt);
        if (!saved) {
            throw new RuntimeException("创建提示词失败");
        }

        // 绑定标签
        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            bindTags(prompt.getId(), request.getTagIds());
        }

        // 创建初始版本快照
        createVersionSnapshot(prompt.getId(), "初始化版本");

        return prompt.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(PromptUpdateRequest request) {
        SysPrompt prompt = this.getById(request.getId());
        if (prompt == null || prompt.getDelFlag() == 1) {
            throw new IllegalArgumentException("提示词不存在");
        }

        // 检查是否锁定
        if (prompt.getIsLocked()) {
            throw new IllegalArgumentException("提示词已锁定，无法修改");
        }

        // 创建版本快照（修改前）
        if (request.getContent() != null || StrUtil.isNotBlank(request.getChangeReason())) {
            createVersionSnapshot(request.getId(), request.getChangeReason());
        }

        // 更新基本信息
        if (StrUtil.isNotBlank(request.getPromptName())) {
            prompt.setPromptName(request.getPromptName());
        }
        if (request.getContent() != null) {
            prompt.setContent(request.getContent());
        }
        if (request.getCategoryId() != null) {
            prompt.setCategoryId(request.getCategoryId());
        }
        if (request.getVersion() != null) {
            prompt.setVersion(request.getVersion());
        }
        if (request.getIsActive() != null) {
            prompt.setIsActive(request.getIsActive());
        }
        if (request.getPriority() != null) {
            prompt.setPriority(request.getPriority());
        }
        if (StrUtil.isNotBlank(request.getRemark())) {
            prompt.setRemark(request.getRemark());
        }

        boolean updated = this.updateById(prompt);

        // 更新标签关联
        if (request.getTagIds() != null) {
            // 先删除旧关联
            LambdaQueryWrapper<SysPromptTagRelation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysPromptTagRelation::getPromptId, request.getId());
            tagRelationMapper.delete(wrapper);

            // 添加新关联
            if (!request.getTagIds().isEmpty()) {
                bindTags(request.getId(), request.getTagIds());
            }
        }

        return updated;
    }

    @Override
    public Boolean delete(Long id) {
        SysPrompt prompt = this.getById(id);
        if (prompt == null) {
            return false;
        }

        // 检查是否锁定
        if (prompt.getIsLocked()) {
            throw new IllegalArgumentException("提示词已锁定，无法删除");
        }

        // 删除标签关联
        LambdaQueryWrapper<SysPromptTagRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptTagRelation::getPromptId, id);
        tagRelationMapper.delete(wrapper);

        return this.removeById(id);
    }

    @Override
    public Boolean toggleActive(Long id, Boolean isActive) {
        SysPrompt prompt = new SysPrompt();
        prompt.setId(id);
        prompt.setIsActive(isActive);
        return sysPromptMapper.updateById(prompt) > 0;
    }

    @Override
    public Boolean toggleLocked(Long id, Boolean isLocked) {
        SysPrompt prompt = new SysPrompt();
        prompt.setId(id);
        prompt.setIsLocked(isLocked);
        return sysPromptMapper.updateById(prompt) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean bindTags(Long promptId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return true;
        }

        // 查询已存在的关联
        LambdaQueryWrapper<SysPromptTagRelation> existsWrapper = new LambdaQueryWrapper<>();
        existsWrapper.eq(SysPromptTagRelation::getPromptId, promptId)
                .in(SysPromptTagRelation::getTagId, tagIds);
        List<SysPromptTagRelation> existsList = tagRelationMapper.selectList(existsWrapper);

        // 已存在的标签ID
        List<Long> existsTagIds = existsList.stream()
                .map(SysPromptTagRelation::getTagId)
                .collect(Collectors.toList());

        // 过滤出需要新增的标签
        List<Long> newTagIds = tagIds.stream()
                .filter(tagId -> !existsTagIds.contains(tagId))
                .collect(Collectors.toList());

        if (!newTagIds.isEmpty()) {
            List<SysPromptTagRelation> relations = new ArrayList<>();
            for (Long tagId : newTagIds) {
                SysPromptTagRelation relation = SysPromptTagRelation.builder()
                        .promptId(promptId)
                        .tagId(tagId)
                        .build();
                relations.add(relation);
            }

            // 批量插入
            for (SysPromptTagRelation relation : relations) {
                tagRelationMapper.insert(relation);
            }
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean unbindTags(Long promptId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return true;
        }

        LambdaQueryWrapper<SysPromptTagRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptTagRelation::getPromptId, promptId)
                .in(SysPromptTagRelation::getTagId, tagIds);
        return tagRelationMapper.delete(wrapper) >= 0;
    }

    @Override
    public Long createVersionSnapshot(Long promptId, String changeReason) {
        SysPrompt prompt = this.getById(promptId);
        if (prompt == null) {
            throw new IllegalArgumentException("提示词不存在");
        }

        // 查询最新版本号
        LambdaQueryWrapper<SysPromptVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptVersion::getPromptId, promptId)
                .eq(SysPromptVersion::getDelFlag, 0)
                .orderByDesc(SysPromptVersion::getCreateTime)
                .last("LIMIT 1");
        SysPromptVersion lastVersion = versionService.getByVersion(promptId, prompt.getVersion());

        SysPromptVersion version = new SysPromptVersion();
        version.setPromptId(promptId);
        version.setPromptCode(prompt.getPromptCode());
        version.setVersion(prompt.getVersion());
        version.setContent(prompt.getContent());
        version.setChangeType(ChangeType.UPDATE);
        version.setChangeReason(changeReason);
        version.setChangeFromVersion(lastVersion != null ? lastVersion.getVersion() : null);

        return versionService.create(version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean rollbackToVersion(Long promptId, Long versionId) {
        SysPrompt prompt = this.getById(promptId);
        if (prompt == null) {
            throw new IllegalArgumentException("提示词不存在");
        }

        SysPromptVersion targetVersion = versionMapper.selectById(versionId);
        if (targetVersion == null || !targetVersion.getPromptId().equals(promptId)) {
            throw new IllegalArgumentException("版本不存在");
        }

        // 检查是否锁定
        if (prompt.getIsLocked()) {
            throw new IllegalArgumentException("提示词已锁定，无法回滚");
        }

        // 创建回滚前快照
        createVersionSnapshot(promptId, "回滚前快照");

        // 恢复内容
        prompt.setContent(targetVersion.getContent());
        prompt.setVersion(targetVersion.getVersion());
        boolean updated = this.updateById(prompt);

        // 记录回滚版本
        SysPromptVersion rollbackVersion = new SysPromptVersion();
        rollbackVersion.setPromptId(promptId);
        rollbackVersion.setPromptCode(prompt.getPromptCode());
        rollbackVersion.setVersion(targetVersion.getVersion() + "-rollback");
        rollbackVersion.setContent(targetVersion.getContent());
        rollbackVersion.setChangeType(ChangeType.ROLLBACK);
        rollbackVersion.setChangeReason("回滚到版本: " + targetVersion.getVersion());
        rollbackVersion.setChangeFromVersion(prompt.getVersion());
        versionService.create(rollbackVersion);

        return updated;
    }

    /**
     * 构建单个 VO
     */
    private PromptVO buildVO(SysPrompt prompt) {
        if (prompt == null) {
            return null;
        }

        return PromptVO.builder()
                .id(prompt.getId())
                .promptCode(prompt.getPromptCode())
                .promptName(prompt.getPromptName())
                .promptType(prompt.getPromptType().name())
                .template(extractTemplate(prompt.getContent()))
                .content(prompt.getContent())
                .templateType(prompt.getTemplateType().name())
                .version(prompt.getVersion())
                .isActive(prompt.getIsActive())
                .isLocked(prompt.getIsLocked())
                .scope(prompt.getScope().name())
                .priority(prompt.getPriority())
                .createTime(prompt.getCreateTime())
                .updateTime(prompt.getUpdateTime())
                .remark(prompt.getRemark())
                .build();
    }

    /**
     * 构建 VO 列表
     */
    private List<PromptVO> buildVOList(List<SysPrompt> list) {
        return list.stream()
                .map(this::buildVO)
                .collect(Collectors.toList());
    }

    /**
     * 从 content 中提取 template
     */
    private String extractTemplate(JsonNode content) {
        if (content == null || !content.has("template")) {
            return null;
        }
        return content.get("template").asText();
    }
}
