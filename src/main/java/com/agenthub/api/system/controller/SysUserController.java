package com.agenthub.api.system.controller;

import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.common.validation.ValidationGroups;
import com.agenthub.api.system.domain.SysUser;
import com.agenthub.api.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


/**
 * 用户管理
 */
@Tag(name = "用户管理")
@RestController
@RequestMapping("/system/user")
public class SysUserController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(SysUserController.class);

    @Autowired
    private ISysUserService userService;

    @Operation(summary = "获取用户列表")
    @PreAuthorize("hasRole('admin')")
    @GetMapping("/list")
    public AjaxResult list(SysUser user, PageQuery pageQuery) {
        PageResult<SysUser> page = userService.selectUserPage(user, pageQuery);
        return success(page);
    }

    @Operation(summary = "根据用户ID获取详细信息")
    @PreAuthorize("hasRole('admin')")
    @GetMapping("/{userId}")
    public AjaxResult getInfo(@PathVariable Long userId) {
        return success(userService.getById(userId));
    }

    @Operation(summary = "新增用户")
    @PreAuthorize("hasRole('admin')")
    @PostMapping
    public AjaxResult add(@Validated(ValidationGroups.Create.class) @RequestBody SysUser user) {
        log.info("接收到新增用户请求: username={}, nickname={}, role={}, phonenumber={}, email={}, password={}",
                user.getUsername(), user.getNickname(), user.getRole(),
                user.getPhonenumber(), user.getEmail(), user.getPassword());
        if (!userService.checkUsernameUnique(user.getUsername())) {
            return error("新增用户'" + user.getUsername() + "'失败，用户名已存在");
        }
        if (!userService.checkEmailUnique(user.getEmail())) {
            return error("新增用户'" + user.getUsername() + "'失败，邮箱账号已存在");
        }
        return success(userService.registerUser(user));
    }

    @Operation(summary = "修改用户")
    @PreAuthorize("hasRole('admin')")
    @PutMapping
    public AjaxResult edit(@Validated(ValidationGroups.Update.class) @RequestBody SysUser user) {
        // 如果密码为空，保留原密码不更新
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            com.agenthub.api.system.domain.SysUser existingUser = userService.getById(user.getUserId());
            if (existingUser != null) {
                user.setPassword(existingUser.getPassword());
            }
        }
        return success(userService.updateById(user));
    }

    @Operation(summary = "删除用户")
    @PreAuthorize("hasRole('admin')")
    @DeleteMapping("/{userIds}")
    public AjaxResult remove(@PathVariable Long[] userIds) {
        return success(userService.removeBatchByIds(java.util.Arrays.asList(userIds)));
    }
}
