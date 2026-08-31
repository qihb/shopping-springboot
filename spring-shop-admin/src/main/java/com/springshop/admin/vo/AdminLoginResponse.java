package com.springshop.admin.vo;

/**
 * 管理员登录响应
 */
public class AdminLoginResponse {

    /** JWT token */
    private String token;

    /** 当前管理员信息（含角色与权限） */
    private AdminUserInfoVO adminUser;

    public AdminLoginResponse() {
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public AdminUserInfoVO getAdminUser() {
        return adminUser;
    }

    public void setAdminUser(AdminUserInfoVO adminUser) {
        this.adminUser = adminUser;
    }
}
