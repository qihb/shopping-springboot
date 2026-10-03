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
    private static final String CART = "cart:";
    private static final String STATS_RECALL_LOCK = "stats:recall:lock";

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

    /** 购物车缓存 key：Redis Hash 结构，field=skuId，value=条目id|数量|是否勾选（DB 为主存，Redis 仅读加速） */
    public static String cart(Long userId) {
        return CART + userId;
    }

    /**
     * 召回圈人任务分布式锁 key
     *
     * <p>{@code @EnableScheduling} 在每个实例各自生效，集群部署时定时任务会同时跑 N 次，
     * 用该锁保证同一时刻只有一个实例执行。Redis 故障时锁失效、退化为「都执行」，
     * 由任务自身的幂等（先删待处理再重建）兜底，不会产生重复数据。
     */
    public static String statsRecallLock() {
        return STATS_RECALL_LOCK;
    }
}
