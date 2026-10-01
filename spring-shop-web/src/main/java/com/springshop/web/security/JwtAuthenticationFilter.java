package com.springshop.web.security;

import com.springshop.common.security.JwtTokenProvider;
import com.springshop.common.security.RedisKeys;
import com.springshop.common.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：每个请求只执行一次，负责从请求头解析 token 并完成认证
 *
 * <p>处理流程：
 * 0. 仅接受 userType=USER 的 token，且校验 Redis 黑名单（登出主动失效）；
 * 1. 读取 {@code Authorization: Bearer <token>} 请求头；
 * 2. 验签解析出用户名，加载用户信息；
 * 3. 构造认证对象写入 {@link SecurityContextHolder}（Spring Security 由此判定已登录）；
 * 4. 同时写入 {@link UserContext}，供 Controller 直接获取当前用户 id。
 *
 * <p>token 无效时不做任何认证设置，请求会落入 Security 的未授权处理（401）。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final StringRedisTemplate stringRedisTemplate;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    @Qualifier("userDetailsServiceImpl") UserDetailsService userDetailsService,
                                    StringRedisTemplate stringRedisTemplate) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
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
     * 校验 token 并写入认证上下文；失败时静默跳过（交由授权层返回 401）
     */
    private void authenticate(String token, HttpServletRequest request) {
        try {
            // 只接受前台用户 token，管理员 token 交由后台过滤链处理；
            // 注意：本过滤器同时被注册为全局过滤器，会在后台链之后执行，
            // 这里不能 clearContext，否则会清掉后台链已写入的管理员认证
            if (!JwtTokenProvider.USER_TYPE_USER.equals(jwtTokenProvider.getUserType(token))) {
                return;
            }
            // 退出登录后的 token 已进黑名单，视为失效
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeys.userTokenBlacklist(token)))) {
                return;
            }
            String username = jwtTokenProvider.getUsername(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
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
