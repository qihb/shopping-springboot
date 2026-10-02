package com.springshop.common.security;

/**
 * 当前请求的客户端上下文（ThreadLocal）
 *
 * <p>由 {@code ClientIdFilter} 在请求进入时从 {@code X-Client-Id} 请求头解析并写入，
 * 请求结束时清理。Service / Controller 通过 {@link #getClientId()} 获取当前端标识，
 * 登录接口据此把 clientId 写入 JWT token。
 *
 * <p>与 {@link UserContext} 一致：一次请求内共享、请求间隔离，且不侵入接口签名。
 */
public final class ClientContext {

    /** 客户端标识请求头名称 */
    public static final String HEADER = "X-Client-Id";

    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();

    private ClientContext() {
    }

    /**
     * 写入客户端标识；非法或空值统一归一化为缺省端（{@link ClientType#DEFAULT}）
     */
    public static void setClientId(String clientId) {
        CLIENT_ID.set(ClientType.from(clientId).getCode());
    }

    /**
     * 获取当前客户端标识，未设置时返回缺省端（{@link ClientType#DEFAULT}）
     */
    public static String getClientId() {
        String clientId = CLIENT_ID.get();
        return clientId != null ? clientId : ClientType.DEFAULT.getCode();
    }

    /**
     * 请求结束时必须调用，否则线程池复用时客户端标识串到下一个请求
     */
    public static void clear() {
        CLIENT_ID.remove();
    }
}
