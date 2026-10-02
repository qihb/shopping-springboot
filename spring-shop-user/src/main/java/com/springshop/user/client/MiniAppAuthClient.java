package com.springshop.user.client;

/**
 * 小程序登录凭证换取客户端（可插拔）
 *
 * <p>抽象「用临时 code 换 openid」这一外部依赖，便于：
 * <ul>
 *   <li>生产接入微信 {@code jscode2session} 接口；</li>
 *   <li>开发 / 测试环境用 mock 实现，避免依赖外网与真实小程序凭证。</li>
 * </ul>
 */
public interface MiniAppAuthClient {

    /**
     * 用小程序登录凭证换取用户 openid
     *
     * @param code {@code wx.login()} 返回的临时登录凭证
     * @return 用户在该小程序下的唯一标识 openid
     */
    String getOpenid(String code);
}
