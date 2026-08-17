package com.springshop.user.vo;

/**
 * 登录成功出参：token + 用户信息
 */
public class LoginResponse {

    /** JWT 凭证，后续请求放入 Authorization: Bearer <token> */
    private String token;

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
