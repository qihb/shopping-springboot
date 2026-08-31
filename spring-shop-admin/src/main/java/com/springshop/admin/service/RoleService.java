package com.springshop.admin.service;

import com.springshop.admin.dto.RoleSaveRequest;
import com.springshop.admin.vo.RoleVO;
import com.springshop.common.dto.PageQuery;
import com.springshop.common.result.PageResult;

import java.util.List;

/**
 * 角色管理服务
 */
public interface RoleService {

    /**
     * 分页查询角色
     */
    PageResult<RoleVO> page(PageQuery pageQuery, String name);

    /**
     * 查询全部启用角色（下拉选择用）
     */
    List<RoleVO> listAll();

    /**
     * 新增角色
     */
    void create(RoleSaveRequest request);

    /**
     * 修改角色
     */
    void update(Long id, RoleSaveRequest request);

    /**
     * 删除角色（已被管理员绑定时禁止删除）
     */
    void delete(Long id);

    /**
     * 给角色分配菜单权限（先删后插）
     */
    void assignMenus(Long roleId, List<Long> menuIds);

    /**
     * 查询角色已分配的菜单 id 集合
     */
    List<Long> listMenuIds(Long roleId);
}
