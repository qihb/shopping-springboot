package com.springshop.common.security;

/**
 * 当前登录用户上下文（ThreadLocal）
 *
 * <p>由认证过滤器在请求进入时写入、请求结束时清理，Controller/Service 通过
 * {@link #getUserId()} 获取当前用户 id，避免在接口入参中反复传递用户标识。
 *
 * <p>为什么用 ThreadLocal：请求由单一线程处理（Tomcat 每请求一线程），
 * ThreadLocal 天然实现「一次请求内共享、请求间隔离」，且不侵入接口签名。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    /**
     * 请求结束时必须调用，否则线程池复用时数据串到下一个请求
     */
    public static void clear() {
        USER_ID.remove();
    }
}
