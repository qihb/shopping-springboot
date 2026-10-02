package com.springshop.common.security;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 客户端类型（多端标识）
 *
 * <p>用于区分同一账号在不同终端的登录来源：网页端、微信小程序端、移动 App 端。
 * 客户端在每个请求的 {@code X-Client-Id} 请求头中携带该标识，登录时写入 JWT token，
 * 便于后续按端做审计、灰度或单端强制下线。
 *
 * <p>解析策略为「宽容兜底」：缺失或无法识别的标识一律回落为 {@link #WEB}，
 * 避免因客户端版本不一致导致请求被拦截。
 */
public enum ClientType {

    /** PC / H5 网页端 */
    WEB("WEB", "网页端"),

    /** 微信小程序端 */
    MINIAPP("MINIAPP", "微信小程序端"),

    /** 移动 App 端 */
    APP("APP", "移动 App 端");

    /** 缺省客户端类型 */
    public static final ClientType DEFAULT = WEB;

    private final String code;

    private final String description;

    ClientType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 把请求头中的原始标识解析为枚举，无法识别时回落为 {@link #DEFAULT}
     *
     * @param code 原始客户端标识（大小写不敏感，允许空白）
     */
    public static ClientType from(String code) {
        if (!StringUtils.hasText(code)) {
            return DEFAULT;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (ClientType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        return DEFAULT;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
