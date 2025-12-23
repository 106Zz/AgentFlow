package com.agenthub.api.system.controller;

import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.system.domain.SysUser;
import com.agenthub.api.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


/**
 * 用户管理
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/system/user")
public class SysUserController extends BaseController {

    @Autowired
    private ISysUserService userService;

    @Operation(summary = "获取用户列表")
    @PreAuthorize("hasRole('ROLE_admin')")
    @GetMapping("/list")
    public AjaxResult list(SysUser user, PageQuery pageQuery) {
        PageResult<SysUser> page = userService.selectUserPage(user, pageQuery);
        return success(page);
    }

    @Operation(summary = "根据用户ID获取详细信息")
    @PreAuthorize("hasRole('ROLE_admin')")
    @GetMapping("/{userId}")
    public AjaxResult getInfo(@PathVariable Long userId) {
        return success(userService.getById(userId));
    }

    @Operation(summary = "新增用户")
    @PreAuthorize("hasRole('ROLE_admin')")
    @PostMapping
    public AjaxResult add(@RequestBody SysUser user) {
        if (!userService.checkUsernameUnique(user.getUsername())) {
            return error("新增用户'" + user.getUsername() + "'失败，用户名已存在");
        }
        if (!userService.checkPhoneUnique(user.getPhonenumber())) {
            return error("新增用户'" + user.getUsername() + "'失败，手机号码已存在");
        }
        if (!userService.checkEmailUnique(user.getEmail())) {
            return error("新增用户'" + user.getUsername() + "'失败，邮箱账号已存在");
        }
        return success(userService.registerUser(user));
    }

    @Operation(summary = "修改用户")
    @PreAuthorize("hasRole('ROLE_admin')")
    @PutMapping
    public AjaxResult edit(@RequestBody SysUser user) {
        return success(userService.updateById(user));
    }

    @Operation(summary = "删除用户")
    @PreAuthorize("hasRole('ROLE_admin')")
    @DeleteMapping("/{userIds}")
    public AjaxResult remove(@PathVariable Long[] userIds) {
        return success(userService.removeBatchByIds(java.util.Arrays.asList(userIds)));
    }
}
