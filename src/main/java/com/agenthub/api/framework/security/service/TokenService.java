package com.agenthub.api.framework.security.service;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.common.constant.Constants;
import com.agenthub.api.system.domain.model.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Token服务
 */
@Component
public class TokenService {

    @Value("${token.secret:abcdefghijklmnopqrstuvwxyz0123456789}")
    private String secret;

    @Value("${token.expireTime:30}")
    private int expireTime;

    protected static final long MILLIS_SECOND = 1000;
    protected static final long MILLIS_MINUTE = 60 * MILLIS_SECOND;
    private static final Long MILLIS_MINUTE_TEN = 20 * 60 * 1000L;

    public LoginUser getLoginUser(HttpServletRequest request) {
        String token = getToken(request);
        if (StrUtil.isNotEmpty(token)) {
            try {
                Claims claims = parseToken(token);
                String uuid = (String) claims.get(Constants.LOGIN_USER_KEY);
                Long userId = claims.get(Constants.JWT_USERID, Long.class);
                String username = claims.get(Constants.JWT_USERNAME, String.class);
                
                // Fix: Extract roles from token
                java.util.List<String> roles = claims.get("roles", java.util.List.class);

                LoginUser user = new LoginUser();
                user.setUserId(userId);
                user.setUsername(username);
                user.setRoles(roles); // Set roles
                user.setToken(token);
                user.setLoginTime(System.currentTimeMillis());
                user.setExpireTime(user.getLoginTime() + expireTime * MILLIS_MINUTE);
                return user;
            } catch (Exception e) {
            }
        }
        return null;
    }

    public void verifyToken(LoginUser loginUser) {
        long expireTime = loginUser.getExpireTime();
        long currentTime = System.currentTimeMillis();
        if (expireTime - currentTime <= MILLIS_MINUTE_TEN) {
            refreshToken(loginUser);
        }
    }

    public void refreshToken(LoginUser loginUser) {
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + expireTime * MILLIS_MINUTE);
    }

    public String createToken(LoginUser loginUser) {
        // Fix: Pass roles to createToken
        String token = createToken(loginUser.getUserId(), loginUser.getUsername(), loginUser.getRoles());
        loginUser.setToken(token);
        return token;
    }

    private String createToken(Long userId, String username, java.util.List<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_USER_KEY, "user_" + userId);
        claims.put(Constants.JWT_USERID, userId);
        claims.put(Constants.JWT_USERNAME, username);
        if (roles != null) {
            claims.put("roles", roles); // Store roles
        }
        return createToken(claims);
    }

    private String createToken(Map<String, Object> claims) {
        return Jwts.builder()
                .claims(claims)
                .signWith(getSecretKey())
                .compact();
    }

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 公开方法：直接解析 token（用于 SSE 等特殊场景）
     */
    public Claims parseTokenDirect(String token) {
        return parseToken(token);
    }

    private String getToken(HttpServletRequest request) {
        // 1. 优先从请求头获取
        String token = request.getHeader(Constants.TOKEN);
        if (StrUtil.isNotEmpty(token) && token.startsWith(Constants.TOKEN_PREFIX)) {
            token = token.replace(Constants.TOKEN_PREFIX, "");
            return token;
        }

        // 2. 从 Cookie 获取（用于 SSE 等不支持自定义头的场景）
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if ("auth_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 3. 从 URL 参数获取（SSE 备用方案）
        token = request.getParameter("token");
        if (StrUtil.isNotEmpty(token)) {
            return token;
        }

        return null;
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
