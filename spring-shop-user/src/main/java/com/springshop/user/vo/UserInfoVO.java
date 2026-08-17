package com.springshop.user.vo;

/**
 * 用户信息出参（对外暴露，不含密码等敏感字段）
 */
public class UserInfoVO {

    private Long id;

    private String username;

    private String nickname;

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
