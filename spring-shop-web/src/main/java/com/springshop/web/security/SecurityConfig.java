package com.springshop.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.result.Result;
import com.springshop.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 核心配置
 *
 * <p>关键设计：
 * <ul>
 *   <li>无状态（STATELESS）：不创建 HttpSession，登录态完全依赖 JWT；</li>
 *   <li>CSRF 关闭：无状态 JWT 方案下不存在基于 cookie 的 CSRF 攻击面；</li>
 *   <li>白名单：注册/登录/文档等接口匿名可访问，其余接口必须携带有效 token；</li>
 *   <li>JwtAuthenticationFilter 挂在 {@link UsernamePasswordAuthenticationFilter} 之前执行。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 密码加密器：BCrypt 自动加盐，同一明文每次加密结果不同，防彩虹表
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                // 关闭 CSRF：无状态 JWT 方案不依赖 cookie
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态会话：不创建、不使用 HttpSession
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 授权规则
                .authorizeHttpRequests(auth -> auth
                        // 白名单：匿名可访问
                        .requestMatchers("/api/auth/**", "/api/health").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**").permitAll()
                        // 其余接口必须已认证
                        .anyRequest().authenticated())
                // 未认证访问受保护接口时返回统一 401 JSON
                .exceptionHandling(eh -> eh.authenticationEntryPoint(unauthorizedEntryPoint()))
                // JWT 过滤器在用户名密码过滤器之前执行
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 未认证（无 token / token 无效）时的响应：401 + 统一响应体
     */
    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(new ObjectMapper().writeValueAsString(Result.fail(ResultCode.UNAUTHORIZED)));
        };
    }
}
