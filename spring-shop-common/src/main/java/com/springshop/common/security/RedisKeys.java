package com.springshop.common.security;

/**
 * Redis key 统一管理
 *
 * <p>所有业务模块的 Redis key 前缀集中在公共模块声明，避免各模块
 * 随手拼接字符串导致 key 冲突或遗忘过期策略。
 */
public final class RedisKeys {

    private static final String ADMIN_LOGIN_FAIL = "admin:login:fail:";
    private static final String ADMIN_TOKEN_BLACKLIST = "admin:token:blacklist:";

    private RedisKeys() {
    }

    /** 管理员登录失败计数 key：记录连续失败次数，用于触发账号临时锁定 */
    public static String adminLoginFailCount(String username) {
        return ADMIN_LOGIN_FAIL + username;
    }

    /** 管理员 token 黑名单 key：退出登录后 token 加入黑名单实现主动失效 */
    public static String adminTokenBlacklist(String token) {
        return ADMIN_TOKEN_BLACKLIST + token;
    }
}
