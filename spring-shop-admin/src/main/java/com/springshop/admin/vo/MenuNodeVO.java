package com.springshop.admin.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点
 *
 * <p>由菜单实体递归组装为树形结构，供前端渲染侧边栏 / 权限树。
 */
public class MenuNodeVO {

    private Long id;

    private Long parentId;

    private String name;

    /** 类型：1 目录 / 2 菜单 / 3 按钮 */
    private Integer type;

    private String path;

    private String permissionCode;

    private String icon;

    private Integer sort;

    private Integer status;

    /** 子节点 */
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
