package com.springshop.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.admin.security.AdminJwtAuthenticationFilter;
import com.springshop.admin.security.AdminUserPrincipal;
import com.springshop.common.result.Result;
import com.springshop.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 核心配置（前后台双过滤链）
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>双链隔离</b>：{@code /api/admin/**} 走 admin 链（管理员认证），其余走前台链，
 *       两条链使用不同的 JWT 过滤器，前台用户 token 无法访问后台接口；</li>
 *   <li>无状态（STATELESS）：不创建 HttpSession，登录态完全依赖 JWT；</li>
 *   <li>CSRF 关闭：无状态 JWT 方案下不存在基于 cookie 的 CSRF 攻击面；</li>
 *   <li>白名单：注册/登录/文档等接口匿名可访问，其余接口必须携带有效 token；</li>
 *   <li>方法级鉴权：{@code @EnableMethodSecurity} 开启 {@code @PreAuthorize}。</li>
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

    /**
     * 管理后台过滤链：只处理 /api/admin/** 请求
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                        AdminJwtAuthenticationFilter adminJwtAuthenticationFilter) throws Exception {
        http.securityMatcher("/api/admin/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 后台登录/退出白名单
                        .requestMatchers("/api/admin/auth/**").permitAll()
                        // 其余接口必须由管理员身份访问（严格校验主体类型，
                        // 防止前台过滤器全局执行产生的认证信息误入后台链）
                        .anyRequest().access(adminPrincipalAuthorizationManager()))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(unauthorizedEntryPoint()))
                .addFilterBefore(adminJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 前台过滤链：处理除 /api/admin/** 外的所有请求
     */
    @Bean
    @Order(2)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
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
                        .requestMatchers("/api/categories/tree", "/api/products", "/api/products/**").permitAll()
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

    /**
     * 后台接口授权管理器：仅允许管理员身份访问
     *
     * <p>前台过滤链与后台过滤链共存的场景下，前台 JWT 过滤器可能已把前台用户
     * 认证信息写入 SecurityContext，因此后台链不能仅用 {@code authenticated()}
     * 判断，必须校验认证主体是 {@link AdminUserPrincipal}，杜绝前台用户越权。
     */
    private AuthorizationManager<RequestAuthorizationContext> adminPrincipalAuthorizationManager() {
        return (authenticationSupplier, context) -> {
            Authentication authentication = authenticationSupplier.get();
            boolean granted = authentication != null
                    && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof AdminUserPrincipal;
            return new AuthorizationDecision(granted);
        };
    }
}
