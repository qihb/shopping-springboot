package com.springshop.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 微信小程序登录请求入参
 *
 * <p>前端调用 {@code wx.login()} 拿到临时登录凭证 {@code code} 后提交给本接口，
 * 后端用 code 换取 openid 完成登录；首次登录会自动创建用户。
 */
public class MiniAppLoginRequest {

    /** wx.login() 返回的临时登录凭证，只能使用一次 */
    @NotBlank(message = "登录凭证 code 不能为空")
    private String code;

    /** 昵称（可选，仅首次自动创建用户时使用） */
    @Size(max = 20, message = "昵称长度不能超过 20")
    private String nickname;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
