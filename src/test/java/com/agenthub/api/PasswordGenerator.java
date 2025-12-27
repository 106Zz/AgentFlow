package com.agenthub.api;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码生成工具（用于生成BCrypt加密密码）
 */
public class PasswordGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        
        // 生成 admin123 的加密密码
        String adminPassword = encoder.encode("admin123");
        System.out.println("admin123 加密后: " + adminPassword);
        
        // 生成 user123 的加密密码
        String userPassword = encoder.encode("user123");
        System.out.println("user123 加密后: " + userPassword);
        
        // 验证当前数据库中的密码
        String dbPassword = "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE/TU.qj6wFKZy";
        System.out.println("\n数据库密码验证 admin123: " + encoder.matches("admin123", dbPassword));
        System.out.println("数据库密码验证 admin: " + encoder.matches("admin", dbPassword));
        System.out.println("数据库密码验证 123456: " + encoder.matches("123456", dbPassword));
        
        // 验证新生成的密码
        System.out.println("\n新密码验证 admin123: " + encoder.matches("admin123", adminPassword));
        System.out.println("新密码验证 user123: " + encoder.matches("user123", userPassword));
        
        // 生成 SQL 更新语句
        System.out.println("\n=== 复制以下 SQL 到数据库执行 ===");
        System.out.println("UPDATE sys_user SET password = '" + adminPassword + "' WHERE username = 'admin';");
        System.out.println("UPDATE sys_user SET password = '" + userPassword + "' WHERE username = 'user';");
    }
}
