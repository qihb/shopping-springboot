package com.springshop.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理员登录响应
 */
@Schema(description = "管理员登录响应")
public class AdminLoginResponse {

    /** JWT token */
    @Schema(description = "JWT token")
    private String token;

    /** 当前管理员信息（含角色与权限） */
    @Schema(description = "当前管理员信息（含角色与权限）")
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
