package com.agenthub.api.system.service;

import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.system.domain.SysUser;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 用户 业务层
 */
public interface ISysUserService extends IService<SysUser> {

    /**
     * 根据用户名查询用户
     */
    SysUser selectUserByUsername(String username);

    /**
     * 注册用户
     */
    boolean registerUser(SysUser user);

    /**
     * 分页查询用户列表
     */
    PageResult<SysUser> selectUserPage(SysUser user, PageQuery pageQuery);

    /**
     * 校验用户名称是否唯一
     */
    boolean checkUsernameUnique(String username);

    /**
     * 校验手机号码是否唯一
     */
    boolean checkPhoneUnique(String phonenumber);

    /**
     * 校验email是否唯一
     */
    boolean checkEmailUnique(String email);
}
