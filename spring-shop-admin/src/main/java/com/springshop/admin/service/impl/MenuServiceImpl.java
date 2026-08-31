package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.admin.dto.MenuSaveRequest;
import com.springshop.admin.entity.Menu;
import com.springshop.admin.mapper.MenuMapper;
import com.springshop.admin.service.MenuService;
import com.springshop.admin.vo.MenuNodeVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单 / 权限管理服务实现
 */
@Service
public class MenuServiceImpl implements MenuService {

    private final MenuMapper menuMapper;

    public MenuServiceImpl(MenuMapper menuMapper) {
        this.menuMapper = menuMapper;
    }

    @Override
    public List<MenuNodeVO> tree() {
        // 一次查出全部菜单，内存中组装树，避免 N+1 查询
        List<MenuNodeVO> nodes = menuMapper.selectList(
                        Wrappers.<Menu>lambdaQuery().orderByAsc(Menu::getSort))
                .stream().map(this::toNode).collect(Collectors.toList());

        Map<Long, List<MenuNodeVO>> childrenMap = nodes.stream()
                .filter(node -> node.getParentId() != null && node.getParentId() != 0)
                .collect(Collectors.groupingBy(MenuNodeVO::getParentId));

        // 顶级节点（parentId == 0）
        return nodes.stream()
                .filter(node -> node.getParentId() == null || node.getParentId() == 0)
                .peek(node -> attachChildren(node, childrenMap))
                .sorted(Comparator.comparing(MenuNodeVO::getSort))
                .collect(Collectors.toList());
    }

    @Override
    public void create(MenuSaveRequest request) {
        Menu menu = new Menu();
        applyRequest(menu, request);
        menu.setSort(request.getSort() == null ? 0 : request.getSort());
        menu.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        menuMapper.insert(menu);
    }

    @Override
    public void update(Long id, MenuSaveRequest request) {
        Menu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(ResultCode.ADMIN_MENU_NOT_FOUND);
        }
        // 不允许把节点挂到自己或子孙节点下，避免形成环
        if (request.getParentId().equals(id) || isDescendant(id, request.getParentId())) {
            throw new BusinessException(ResultCode.ADMIN_MENU_NOT_FOUND);
        }
        applyRequest(menu, request);
        menuMapper.updateById(menu);
    }

    @Override
    public void delete(Long id) {
        Long childCount = menuMapper.selectCount(
                Wrappers.<Menu>lambdaQuery().eq(Menu::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException(ResultCode.ADMIN_MENU_HAS_CHILDREN);
        }
        menuMapper.deleteById(id);
    }

    private void applyRequest(Menu menu, MenuSaveRequest request) {
        menu.setParentId(request.getParentId());
        menu.setName(request.getName());
        menu.setType(request.getType());
        menu.setPath(request.getPath());
        menu.setPermissionCode(request.getPermissionCode());
        menu.setIcon(request.getIcon());
        menu.setSort(request.getSort());
        menu.setStatus(request.getStatus());
    }

    /**
     * 递归挂载子节点
     */
    private void attachChildren(MenuNodeVO parent, Map<Long, List<MenuNodeVO>> childrenMap) {
        List<MenuNodeVO> children = childrenMap.getOrDefault(parent.getId(), List.of());
        children.sort(Comparator.comparing(MenuNodeVO::getSort));
        parent.setChildren(children);
        children.forEach(child -> attachChildren(child, childrenMap));
    }

    /**
     * 判断 candidateId 是否为 targetId 的子孙节点（用于阻止形成环）
     */
    private boolean isDescendant(Long candidateId, Long targetId) {
        if (targetId == null || targetId == 0) {
            return false;
        }
        Menu target = menuMapper.selectById(targetId);
        if (target == null || target.getParentId() == 0) {
            return false;
        }
        if (target.getParentId().equals(candidateId)) {
            return true;
        }
        return isDescendant(candidateId, target.getParentId());
    }

    private MenuNodeVO toNode(Menu menu) {
        MenuNodeVO node = new MenuNodeVO();
        node.setId(menu.getId());
        node.setParentId(menu.getParentId());
        node.setName(menu.getName());
        node.setType(menu.getType());
        node.setPath(menu.getPath());
        node.setPermissionCode(menu.getPermissionCode());
        node.setIcon(menu.getIcon());
        node.setSort(menu.getSort());
        node.setStatus(menu.getStatus());
        return node;
    }
}
