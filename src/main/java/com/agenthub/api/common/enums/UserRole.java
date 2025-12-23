package com.agenthub.api.common.enums;

/**
 * 用户角色枚举
 */
public enum UserRole {

    ADMIN("admin", "管理员"),
    USER("user", "普通用户");

    private final String code;
    private final String name;

    UserRole(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
