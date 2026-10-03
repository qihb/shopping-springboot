package com.springshop.web.exception;

import com.springshop.common.result.Result;
import com.springshop.common.result.ResultCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 鉴权失败（无权限访问）的统一响应
 *
 * <p>为什么需要单独一个 Advice：
 * <ul>
 *   <li>{@code @PreAuthorize} 抛出的 {@link AccessDeniedException}（含其子类
 *       {@code AuthorizationDeniedException}）发生在 Controller 调用期，
 *       此时请求已经越过 Security 过滤链，不会经过 {@code ExceptionTranslationFilter}，
 *       而是冒泡到 DispatcherServlet；</li>
 *   <li>若交给 {@code GlobalExceptionHandler} 的 {@code Exception} 兜底分支，
 *       会被吞成「系统内部错误（500）」，前端无法区分「没权限」和「服务挂了」，
 *       排查时也容易被误导；</li>
 *   <li>Spring Security 的异常类型只存在于 web 模块——{@code spring-shop-common}
 *       刻意不依赖 security（保持公共模块轻量），因此这个 Advice 放在 web 模块，
 *       而不是塞进 common 的 {@code GlobalExceptionHandler}。</li>
 * </ul>
 *
 * <p>返回 HTTP 403 + 统一响应体，与过滤链层面的未认证响应（HTTP 401）保持同一套语义：
 * 认证/授权类失败使用真实 HTTP 状态码，业务类失败才走「HTTP 200 + 业务码」。
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} 保证它先于 common 的通用兜底 Advice 被匹配；
 * 其他异常类型在本类中没有对应处理方法，会自动落回 {@code GlobalExceptionHandler}。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.fail(ResultCode.FORBIDDEN);
    }
}
