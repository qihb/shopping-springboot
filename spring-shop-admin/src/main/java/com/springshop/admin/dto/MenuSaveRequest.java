package com.springshop.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 菜单保存入参（新增 / 修改共用）
 */
public class MenuSaveRequest {

    /** 父菜单 id，0 表示顶级 */
    @NotNull(message = "父菜单 id 不能为空")
    private Long parentId;

    /** 菜单名称 */
    @NotBlank(message = "菜单名称不能为空")
    private String name;

    /** 类型：1 目录 / 2 菜单 / 3 按钮 */
    @NotNull(message = "菜单类型不能为空")
    @Min(value = 1, message = "菜单类型取值 1~3")
    @Max(value = 3, message = "菜单类型取值 1~3")
    private Integer type;

    /** 前端路由路径 */
    private String path;

    /** 权限标识，如 product:sku:edit（按钮级权限用） */
    private String permissionCode;

    /** 菜单图标 */
    private String icon;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
