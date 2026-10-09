package com.springshop.common.config;

import com.springshop.common.dto.PageQuery;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分页参数名守卫：把「参数名写错」从静默忽略改成明确报错
 *
 * <p><b>为什么需要它</b>：{@link PageQuery} 只认 {@code current} / {@code size}。
 * Spring MVC 对「没有被任何入参接收的查询参数」既不报错也不告警，直接丢掉。
 * 于是 {@code GET /api/products?pageNo=3&pageSize=2} 会返回 HTTP 200 与
 * {@code current=1, size=10}——第一页十条。调用方以为在翻第 3 页，拿到的却是第 1 页；
 * 这类错误联调时极难发现，因为响应体看起来完全正常，没有任何信号。
 *
 * <p><b>为什么只拦「绑定 PageQuery 的接口」</b>：{@code page} / {@code limit} 这类名字
 * 在别的接口上可能是合法参数——本项目 {@code /api/admin/stats/recall/**} 就真的用
 * {@code limit} 表示「取前 N 条」。全局拉黑会误伤它们。所以先判断这个接口是不是
 * 真的用 {@link PageQuery} 接收分页参数，只有「是」的时候才检查参数名。
 *
 * <p><b>为什么做成拦截器而不是过滤器</b>：拦截器抛出的异常会走
 * {@code DispatcherServlet} 的异常解析链，被
 * {@link com.springshop.common.exception.GlobalExceptionHandler} 兜住，
 * 按项目统一的 {@code Result} 格式返回；过滤器里抛异常拿不到这套格式，只能手写 JSON。
 *
 * <p>已知取舍：{@code request.getParameter} 也会读到 {@code x-www-form-urlencoded}
 * 表单体里的字段，所以理论上一个「表单字段叫 page」的 POST 也会被拦。
 * 本项目所有绑定 {@link PageQuery} 的接口都是 GET 查询，没有这种组合。
 */
public class PageParamGuardInterceptor implements HandlerInterceptor {

    /**
     * 常见的错误参数名 → 本接口正确的参数名
     *
     * <p>用 {@link LinkedHashMap} 而不是 {@code Map.ofEntries}：后者迭代顺序不确定，
     * 同时传多个错误参数时「报哪一个」会随机变化，对排查和测试都不友好。
     */
    private static final Map<String, String> WRONG_PARAMS = new LinkedHashMap<>();

    static {
        WRONG_PARAMS.put("pageNo", "current");
        WRONG_PARAMS.put("pageNum", "current");
        WRONG_PARAMS.put("pageIndex", "current");
        WRONG_PARAMS.put("currentPage", "current");
        WRONG_PARAMS.put("page", "current");
        WRONG_PARAMS.put("pageSize", "size");
        WRONG_PARAMS.put("perPage", "size");
        WRONG_PARAMS.put("rows", "size");
        WRONG_PARAMS.put("limit", "size");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod) || !bindsPageQuery(handlerMethod)) {
            return true;
        }
        for (Map.Entry<String, String> wrong : WRONG_PARAMS.entrySet()) {
            if (request.getParameter(wrong.getKey()) != null) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), message(wrong));
            }
        }
        return true;
    }

    /**
     * 这个接口是否用 {@link PageQuery} 接收分页参数
     *
     * <p>用参数类型判断而不是看 URL：分页 DTO 可以出现在任何模块、任何路径下，
     * 而类型是编译期的事实，不会随着路径调整而失效。
     */
    private boolean bindsPageQuery(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (PageQuery.class.isAssignableFrom(parameter.getParameterType())) {
                return true;
            }
        }
        return false;
    }

    private String message(Map.Entry<String, String> wrong) {
        return "分页参数名不正确：本接口的分页参数是「current」（页码）与「size」（每页大小），"
                + "不支持「" + wrong.getKey() + "」，请改用「" + wrong.getValue() + "」";
    }
}
