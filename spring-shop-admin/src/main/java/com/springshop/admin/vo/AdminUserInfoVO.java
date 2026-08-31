package com.springshop.admin.vo;

import java.util.List;

/**
 * 当前登录管理员信息
 *
 * <p>包含角色编码与权限标识集合，前端据此渲染菜单与按钮级权限。
 */
public class AdminUserInfoVO {

    private Long id;

    private String username;

    private String realName;

    private String phone;

    /** 角色编码集合，如 ["ADMIN"] */
    private List<String> roles;

    /** 权限标识集合，如 ["product:sku:edit"] */
    private List<String> permissions;

    public AdminUserInfoVO() {
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

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }
}
