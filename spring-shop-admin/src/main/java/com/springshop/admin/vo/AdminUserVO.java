package com.springshop.admin.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员信息（对外不暴露密码、逻辑删除等内部字段）
 */
public class AdminUserVO {

    private Long id;

    private String username;

    private String realName;

    private String phone;

    /** 账号状态：1 启用 / 0 禁用 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    private LocalDateTime createTime;

    /** 已绑定的角色 id 集合，供前端角色选择器回显 */
    private List<Long> roleIds;

    /** 已绑定的角色名称集合，供列表直接展示 */
    private List<String> roleNames;

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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }

    public List<String> getRoleNames() {
        return roleNames;
    }

    public void setRoleNames(List<String> roleNames) {
        this.roleNames = roleNames;
    }
}
