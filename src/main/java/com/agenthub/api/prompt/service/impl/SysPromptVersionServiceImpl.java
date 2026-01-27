package com.agenthub.api.prompt.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.agenthub.api.prompt.domain.entity.SysPromptVersion;
import com.agenthub.api.prompt.mapper.SysPromptMapper;
import com.agenthub.api.prompt.mapper.SysPromptVersionMapper;
import com.agenthub.api.prompt.service.ISysPromptVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 提示词版本 Service 实现
 *
 * @author AgentHub
 * @since 2026-01-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPromptVersionServiceImpl extends ServiceImpl<SysPromptVersionMapper, SysPromptVersion> implements ISysPromptVersionService {

    private final SysPromptVersionMapper sysPromptVersionMapper;
    private final SysPromptMapper sysPromptMapper;

    @Override
    public List<SysPromptVersion> listByPromptId(Long promptId) {
        LambdaQueryWrapper<SysPromptVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptVersion::getPromptId, promptId)
                .eq(SysPromptVersion::getDelFlag, 0)
                .orderByDesc(SysPromptVersion::getCreateTime);
        return sysPromptVersionMapper.selectList(wrapper);
    }

    @Override
    public SysPromptVersion getLatestByCode(String promptCode) {
        // 先通过 promptCode 查询提示词
        LambdaQueryWrapper<SysPrompt> promptWrapper = new LambdaQueryWrapper<>();
        promptWrapper.eq(SysPrompt::getPromptCode, promptCode)
                .eq(SysPrompt::getDelFlag, 0);
        SysPrompt prompt = sysPromptMapper.selectOne(promptWrapper);

        if (prompt == null) {
            return null;
        }

        // 查询该提示词的最新版本
        LambdaQueryWrapper<SysPromptVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptVersion::getPromptId, prompt.getId())
                .eq(SysPromptVersion::getDelFlag, 0)
                .orderByDesc(SysPromptVersion::getCreateTime)
                .last("LIMIT 1");
        return sysPromptVersionMapper.selectOne(wrapper);
    }

    @Override
    public SysPromptVersion getByVersion(Long promptId, String version) {
        LambdaQueryWrapper<SysPromptVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPromptVersion::getPromptId, promptId)
                .eq(SysPromptVersion::getVersion, version)
                .eq(SysPromptVersion::getDelFlag, 0);
        return sysPromptVersionMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysPromptVersion version) {
        if (version.getPromptId() == null) {
            throw new IllegalArgumentException("提示词ID不能为空");
        }

        // 验证提示词是否存在
        SysPrompt prompt = sysPromptMapper.selectById(version.getPromptId());
        if (prompt == null || prompt.getDelFlag() == 1) {
            throw new IllegalArgumentException("提示词不存在");
        }

        // 设置 promptCode
        if (StrUtil.isBlank(version.getPromptCode())) {
            version.setPromptCode(prompt.getPromptCode());
        }

        boolean saved = this.save(version);
        if (!saved) {
            throw new RuntimeException("创建版本失败");
        }
        return version.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Long id) {
        SysPromptVersion version = this.getById(id);
        if (version == null || version.getDelFlag() == 1) {
            return false;
        }
        return this.removeById(id);
    }
}
