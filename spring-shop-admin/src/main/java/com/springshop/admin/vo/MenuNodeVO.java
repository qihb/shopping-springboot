package com.springshop.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点
 *
 * <p>由菜单实体递归组装为树形结构，供前端渲染侧边栏 / 权限树。
 */
@Schema(description = "菜单树节点")
public class MenuNodeVO {

    @Schema(description = "菜单 id")
    private Long id;

    @Schema(description = "父菜单 id，0 表示顶级")
    private Long parentId;

    @Schema(description = "菜单名称")
    private String name;

    /** 类型：1 目录 / 2 菜单 / 3 按钮 */
    @Schema(description = "类型：1 目录 / 2 菜单 / 3 按钮")
    private Integer type;

    @Schema(description = "前端路由路径")
    private String path;

    @Schema(description = "权限标识，如 product:sku:edit（按钮级权限用）")
    private String permissionCode;

    @Schema(description = "菜单图标")
    private String icon;

    @Schema(description = "排序值，越小越靠前")
    private Integer sort;

    @Schema(description = "状态：1 启用 / 0 停用")
    private Integer status;

    /** 子节点 */
    @Schema(description = "子节点")
    private List<MenuNodeVO> children = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public List<MenuNodeVO> getChildren() {
        return children;
    }

    public void setChildren(List<MenuNodeVO> children) {
        this.children = children;
    }
}
