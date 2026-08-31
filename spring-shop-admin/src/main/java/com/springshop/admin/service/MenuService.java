package com.springshop.admin.service;

import com.springshop.admin.dto.MenuSaveRequest;
import com.springshop.admin.vo.MenuNodeVO;

import java.util.List;

/**
 * 菜单 / 权限管理服务
 */
public interface MenuService {

    /**
     * 查询全部菜单（树形结构）
     */
    List<MenuNodeVO> tree();

    /**
     * 新增菜单
     */
    void create(MenuSaveRequest request);

    /**
     * 修改菜单
     */
    void update(Long id, MenuSaveRequest request);

    /**
     * 删除菜单（存在子节点时禁止删除）
     */
    void delete(Long id);
}
