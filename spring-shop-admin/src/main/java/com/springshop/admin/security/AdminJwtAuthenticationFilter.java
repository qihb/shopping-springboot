package com.springshop.admin.security;

import com.springshop.common.security.JwtTokenProvider;
import com.springshop.common.security.RedisKeys;
import com.springshop.common.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理后台 JWT 认证过滤器：只处理 /api/admin/** 下的请求
 *
 * <p>与前台 {@code JwtAuthenticationFilter} 的区别：
 * <ol>
 *   <li>仅接受 userType=ADMIN 的 token（前台用户 token 无法访问后台接口）；</li>
 *   <li>校验 token 是否已进入 Redis 黑名单（退出登录后主动失效）。</li>
 * </ol>
 */
@Component
public class AdminJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final AdminUserDetailsService adminUserDetailsService;
    private final StringRedisTemplate stringRedisTemplate;

    public AdminJwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                        AdminUserDetailsService adminUserDetailsService,
                                        StringRedisTemplate stringRedisTemplate) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.adminUserDetailsService = adminUserDetailsService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            authenticate(token, request);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 关键：请求结束必须清理 ThreadLocal，否则线程池复用时用户信息串到下一个请求
            UserContext.clear();
        }
    }

    /**
     * 校验 token 并写入认证上下文；失败时清除上下文（避免前台过滤器已写入的认证误入后台链）
     */
    private void authenticate(String token, HttpServletRequest request) {
        try {
            // 只接受管理员 token，前台用户 token 直接视为未认证
            if (!JwtTokenProvider.USER_TYPE_ADMIN.equals(jwtTokenProvider.getUserType(token))) {
                SecurityContextHolder.clearContext();
                return;
            }
            // 退出登录后的 token 已进黑名单，视为失效
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeys.adminTokenBlacklist(token)))) {
                SecurityContextHolder.clearContext();
                return;
            }
            String username = jwtTokenProvider.getUsername(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                AdminUserPrincipal principal =
                        (AdminUserPrincipal) adminUserDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                UserContext.setUserId(jwtTokenProvider.getUserId(token));
            }
        } catch (Exception e) {
            // token 过期、签名非法等一律视为未登录
            SecurityContextHolder.clearContext();
        }
    }
}
