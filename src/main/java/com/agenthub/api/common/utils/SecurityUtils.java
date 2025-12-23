package com.agenthub.api.common.utils;

import com.agenthub.api.common.constant.Constants;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.system.domain.model.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 安全工具类
 */
public class SecurityUtils {

    public static LoginUser getLoginUser() {
        try {
            return (LoginUser) getAuthentication().getPrincipal();
        } catch (Exception e) {
            throw new ServiceException("获取用户信息异常");
        }
    }

    public static Authentication getAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public static String encryptPassword(String password) {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        return passwordEncoder.encode(password);
    }

    public static boolean matchesPassword(String rawPassword, String encodedPassword) {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    public static boolean isAdmin(Long userId) {
        return userId != null && 1L == userId;
    }

    public static Long getUserId() {
        try {
            return getLoginUser().getUserId();
        } catch (Exception e) {
            throw new ServiceException("获取用户ID异常");
        }
    }

    public static String getUsername() {
        try {
            return getLoginUser().getUsername();
        } catch (Exception e) {
            throw new ServiceException("获取用户名异常");
        }
    }

    public static boolean hasRole(String role) {
        LoginUser loginUser = getLoginUser();
        if (loginUser == null || loginUser.getRoles() == null || loginUser.getRoles().isEmpty()) {
            return false;
        }
        return loginUser.getRoles().contains(role);
    }

    public static boolean isAdmin() {
        return hasRole(Constants.ADMIN_ROLE);
    }
}
