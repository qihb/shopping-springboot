package com.springshop.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户信息出参（对外暴露，不含密码等敏感字段）
 */
@Schema(description = "用户信息")
public class UserInfoVO {

    @Schema(description = "用户 id")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "手机号")
    private String phone;

    public UserInfoVO() {
    }

    public UserInfoVO(Long id, String username, String nickname, String phone) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        this.phone = phone;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
