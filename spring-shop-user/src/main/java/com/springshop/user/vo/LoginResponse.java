package com.springshop.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录成功出参：token + 用户信息
 */
@Schema(description = "登录成功出参")
public class LoginResponse {

    /** JWT 凭证，后续请求放入 Authorization: Bearer <token> */
    @Schema(description = "JWT 凭证，后续请求放入 Authorization 请求头（Bearer {token}）")
    private String token;

    @Schema(description = "用户信息")
    private UserInfoVO user;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public UserInfoVO getUser() {
        return user;
    }

    public void setUser(UserInfoVO user) {
        this.user = user;
    }
}
