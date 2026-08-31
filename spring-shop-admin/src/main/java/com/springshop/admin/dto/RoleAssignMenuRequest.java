package com.springshop.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 给角色分配菜单权限入参
 */
public class RoleAssignMenuRequest {

    /** 角色 id */
    @NotNull(message = "角色 id 不能为空")
    private Long roleId;

    /** 分配的菜单 id 集合（可为空集合表示清空权限） */
    @NotEmpty(message = "菜单 id 集合不能为空")
    private List<Long> menuIds;

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public List<Long> getMenuIds() {
        return menuIds;
    }

    public void setMenuIds(List<Long> menuIds) {
        this.menuIds = menuIds;
    }
}
