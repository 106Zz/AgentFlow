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
                
                LoginUser user = new LoginUser();
                user.setUserId(userId);
                user.setUsername(username);
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
        String token = createToken(loginUser.getUserId(), loginUser.getUsername());
        loginUser.setToken(token);
        return token;
    }

    private String createToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_USER_KEY, "user_" + userId);
        claims.put(Constants.JWT_USERID, userId);
        claims.put(Constants.JWT_USERNAME, username);
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

    private String getToken(HttpServletRequest request) {
        String token = request.getHeader(Constants.TOKEN);
        if (StrUtil.isNotEmpty(token) && token.startsWith(Constants.TOKEN_PREFIX)) {
            token = token.replace(Constants.TOKEN_PREFIX, "");
        }
        return token;
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
