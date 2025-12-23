package com.agenthub.api.system.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.system.domain.SysUser;
import com.agenthub.api.system.mapper.SysUserMapper;
import com.agenthub.api.system.service.ISysUserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 用户 业务层处理
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    @Override
    public SysUser selectUserByUsername(String username) {
        return this.lambdaQuery()
                .eq(SysUser::getUsername, username)
                .one();
    }

    @Override
    public boolean registerUser(SysUser user) {
        // 校验用户名唯一性
        if (!checkUsernameUnique(user.getUsername())) {
            throw new ServiceException("注册用户'" + user.getUsername() + "'失败，用户名已存在");
        }
        
        // 加密密码
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        
        // 设置默认值
        if (StrUtil.isEmpty(user.getRole())) {
            user.setRole("user");
        }
        if (StrUtil.isEmpty(user.getStatus())) {
            user.setStatus("0");
        }
        if (StrUtil.isEmpty(user.getNickname())) {
            user.setNickname(user.getUsername());
        }
        
        return this.save(user);
    }

    @Override
    public PageResult<SysUser> selectUserPage(SysUser user, PageQuery pageQuery) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StrUtil.isNotEmpty(user.getUsername()), SysUser::getUsername, user.getUsername())
                .like(StrUtil.isNotEmpty(user.getNickname()), SysUser::getNickname, user.getNickname())
                .eq(StrUtil.isNotEmpty(user.getStatus()), SysUser::getStatus, user.getStatus())
                .eq(StrUtil.isNotEmpty(user.getRole()), SysUser::getRole, user.getRole())
                .orderByDesc(SysUser::getCreateTime);
        
        IPage<SysUser> page = this.page(pageQuery.build(), wrapper);
        return PageResult.build(page);
    }

    @Override
    public boolean checkUsernameUnique(String username) {
        return this.lambdaQuery()
                .eq(SysUser::getUsername, username)
                .count() == 0;
    }

    @Override
    public boolean checkPhoneUnique(String phonenumber) {
        if (StrUtil.isEmpty(phonenumber)) {
            return true;
        }
        return this.lambdaQuery()
                .eq(SysUser::getPhonenumber, phonenumber)
                .count() == 0;
    }

    @Override
    public boolean checkEmailUnique(String email) {
        if (StrUtil.isEmpty(email)) {
            return true;
        }
        return this.lambdaQuery()
                .eq(SysUser::getEmail, email)
                .count() == 0;
    }
}
