package com.springshop.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 给管理员分配角色入参
 */
@Schema(description = "给管理员分配角色入参")
public class AdminRoleAssignRequest {

    /** 角色 id 集合，传空集合表示清空该管理员的角色 */
    @Schema(description = "角色 id 集合，传空集合表示清空该管理员的角色")
    @NotNull(message = "角色 id 集合不能为空")
    private List<Long> roleIds;

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
