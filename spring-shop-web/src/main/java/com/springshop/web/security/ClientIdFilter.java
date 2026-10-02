package com.springshop.web.security;

import com.springshop.common.security.ClientContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 客户端标识过滤器（多端支持）
 *
 * <p>从请求头 {@code X-Client-Id} 读取客户端标识（WEB / MINIAPP / APP），
 * 归一化后写入 {@link ClientContext}，供登录接口把 clientId 写入 JWT token。
 *
 * <p>关键设计：
 * <ul>
 *   <li>{@code Ordered.HIGHEST_PRECEDENCE + 10}：紧随 {@code TraceIdFilter} 之后、
 *       早于 Spring Security 过滤链执行，保证认证与业务处理阶段都能读到端标识；</li>
 *   <li>缺失或非法标识回落为缺省端（WEB），不因客户端差异拦截请求；</li>
 *   <li>finally 中清理 ThreadLocal，避免线程池复用时标识串请求。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClientIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ClientContext.setClientId(request.getHeader(ClientContext.HEADER));
        try {
            filterChain.doFilter(request, response);
        } finally {
            ClientContext.clear();
        }
    }
}
