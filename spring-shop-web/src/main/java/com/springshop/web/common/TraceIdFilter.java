package com.springshop.web.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 链路追踪过滤器
 *
 * <p>关键设计：
 * <ul>
 *   <li>优先复用上游服务传来的 {@code X-Trace-Id} 请求头，保证跨服务链路串联；
 *       未携带时生成 8 位随机 ID（UUID 前缀，单实例内足够唯一）；</li>
 *   <li>traceId 同时写入 MDC（供日志 pattern 的 {@code %X{traceId}} 输出）
 *       与响应头（便于前端/调用方拿到链路 ID 反馈问题）；</li>
 *   <li>{@code Ordered.HIGHEST_PRECEDENCE} 保证在 Spring Security 过滤链之前执行，
 *       认证失败等安全日志也能带上 traceId；</li>
 *   <li>finally 中清理 MDC，避免线程池复用时 traceId 串线程。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * 链路追踪 ID 的请求/响应头名称
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * MDC 中存放 traceId 的键（与日志 pattern 中 %X{traceId} 对应）
     */
    private static final String MDC_TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            // 上游未指定 traceId 时自动生成
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(MDC_TRACE_ID_KEY, traceId);
        // 在业务处理前先回写响应头，确保异常场景下响应仍携带 traceId
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }
}
