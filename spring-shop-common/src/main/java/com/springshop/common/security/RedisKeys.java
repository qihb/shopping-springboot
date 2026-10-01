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
    private static final String USER_LOGIN_FAIL = "user:login:fail:";
    private static final String USER_TOKEN_BLACKLIST = "user:token:blacklist:";
    private static final String PRODUCT_DETAIL = "product:detail:";
    private static final String CATEGORY_TREE = "category:tree";

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

    /** 用户登录失败计数 key：记录连续失败次数，用于触发账号临时锁定 */
    public static String userLoginFailCount(String username) {
        return USER_LOGIN_FAIL + username;
    }

    /** 用户 token 黑名单 key：退出登录后 token 加入黑名单实现主动失效 */
    public static String userTokenBlacklist(String token) {
        return USER_TOKEN_BLACKLIST + token;
    }

    /** 商品详情缓存 key：value 为 ProductDetailVO 的 JSON 串（不存在的商品存空值占位符） */
    public static String productDetail(Long productId) {
        return PRODUCT_DETAIL + productId;
    }

    /** 分类树缓存 key：value 为分类树列表的 JSON 串 */
    public static String categoryTree() {
        return CATEGORY_TREE;
    }
}
