package com.springshop.user.client.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.user.client.MiniAppAuthClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/**
 * 微信小程序凭证客户端
 *
 * <p>行为由配置驱动：
 * <ul>
 *   <li>已配置 {@code miniapp.appid} 与 {@code miniapp.secret}：调用微信官方
 *       {@code sns/jscode2session} 接口用 code 换取 openid；</li>
 *   <li>未配置（本地开发 / 测试）：进入 mock 模式，openid 由 code 确定性派生
 *       （同一 code 恒定得到同一 openid），保证登录流程可完整走通且不依赖外网。</li>
 * </ul>
 *
 * <p>注意：mock 模式仅用于开发与测试，生产必须通过环境变量注入真实小程序凭证。
 */
@Component
public class WeChatMiniAppAuthClient implements MiniAppAuthClient {

    private static final String MOCK_OPENID_PREFIX = "mock_openid_";

    private final String appid;

    private final String secret;

    private final RestClient restClient;

    public WeChatMiniAppAuthClient(@Value("${miniapp.appid:}") String appid,
                                   @Value("${miniapp.secret:}") String secret) {
        this.appid = appid;
        this.secret = secret;
        this.restClient = RestClient.builder().baseUrl("https://api.weixin.qq.com").build();
    }

    @Override
    public String getOpenid(String code) {
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(secret)) {
            return mockOpenid(code);
        }
        return fetchOpenidFromWeChat(code);
    }

    /**
     * mock 模式：由 code 确定性派生 openid，同一 code 恒定映射到同一用户
     */
    private String mockOpenid(String code) {
        return MOCK_OPENID_PREFIX + DigestUtils.md5DigestAsHex(code.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 调用微信 jscode2session 接口换取 openid；code 失效或接口异常统一抛业务异常
     */
    private String fetchOpenidFromWeChat(String code) {
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/sns/jscode2session")
                            .queryParam("appid", appid)
                            .queryParam("secret", secret)
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            String openid = body == null ? null : body.path("openid").asText(null);
            if (!StringUtils.hasText(openid)) {
                throw new BusinessException(ResultCode.MINIAPP_AUTH_FAILED);
            }
            return openid;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.MINIAPP_AUTH_FAILED);
        }
    }
}
