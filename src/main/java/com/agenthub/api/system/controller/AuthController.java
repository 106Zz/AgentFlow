package com.agenthub.api.system.controller;

import com.agenthub.api.common.base.BaseController;
import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.framework.security.service.TokenService;
import com.agenthub.api.system.domain.SysUser;
import com.agenthub.api.system.domain.model.LoginBody;
import com.agenthub.api.system.domain.model.LoginUser;
import com.agenthub.api.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/auth")
public class AuthController extends BaseController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ISysUserService userService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public AjaxResult login(@Valid @RequestBody LoginBody loginBody) {
        // 用户验证
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginBody.getUsername(), loginBody.getPassword())
        );
        
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        
        // 生成token
        String token = tokenService.createToken(loginUser);
        
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", loginUser.getUserId());
        result.put("username", loginUser.getUsername());
        result.put("roles", loginUser.getRoles());
        
        return success("登录成功", result);
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public AjaxResult register(@RequestBody SysUser user) {
        // 普通用户注册，默认角色为 user
        user.setRole("user");
        // 如果没有设置状态，默认为正常
        if (user.getStatus() == null || user.getStatus().isEmpty()) {
            user.setStatus("0");
        }
        userService.registerUser(user);
        return success("注册成功");
    }
}
