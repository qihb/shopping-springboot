package com.springshop.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与验签工具
 *
 * <p>JWT 是无状态凭证：服务端不保存登录态，token 内嵌用户信息并由
 * HMAC 签名防篡改，验签通过即信任其内容。
 *
 * <p>注意：secret 至少 32 字节（HS256 要求），生产中必须通过环境变量覆盖默认值。
 */
@Component
public class JwtTokenProvider {

    /** token 持有者类型：管理后台用户 */
    public static final String USER_TYPE_ADMIN = "ADMIN";

    /** token 持有者类型：前台用户 */
    public static final String USER_TYPE_USER = "USER";

    private static final String CLAIM_USER_ID = "userId";

    private static final String CLAIM_USER_TYPE = "userType";

    private final SecretKey key;

    /** 过期时间（毫秒） */
    private final long expiration;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    /**
     * 签发前台用户 token（userType 固定为 USER）
     *
     * @param userId   用户 id（存入 claim，供 Controller 通过 UserContext 使用）
     * @param username 用户名（作为 subject）
     */
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, USER_TYPE_USER);
    }

    /**
     * 签发 token
     *
     * @param userId   用户 id（存入 claim，供 Controller 通过 UserContext 使用）
     * @param username 用户名（作为 subject）
     * @param userType 持有者类型：{@link #USER_TYPE_ADMIN} / {@link #USER_TYPE_USER}
     */
    public String generateToken(Long userId, String username, String userType) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USER_TYPE, userType)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token，验签失败或过期时抛出异常
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 token 中提取用户名（验签失败抛 JwtException）
     */
    public String getUsername(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从 token 中提取用户 id
     */
    public Long getUserId(String token) {
        return parseClaims(token).get(CLAIM_USER_ID, Long.class);
    }

    /**
     * 从 token 中提取持有者类型（ADMIN / USER），用于区分前后台认证链
     */
    public String getUserType(String token) {
        return parseClaims(token).get(CLAIM_USER_TYPE, String.class);
    }

    /**
     * 校验 token 是否合法且未过期
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
