package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.admin.dto.RoleSaveRequest;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Role;
import com.springshop.admin.entity.RoleMenu;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.admin.mapper.RoleMenuMapper;
import com.springshop.admin.service.RoleService;
import com.springshop.admin.vo.RoleVO;
import com.springshop.common.dto.PageQuery;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色管理服务实现
 */
@Service
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final AdminUserRoleMapper adminUserRoleMapper;
    private final RoleMenuMapper roleMenuMapper;

    public RoleServiceImpl(RoleMapper roleMapper,
                           AdminUserRoleMapper adminUserRoleMapper,
                           RoleMenuMapper roleMenuMapper) {
        this.roleMapper = roleMapper;
        this.adminUserRoleMapper = adminUserRoleMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

    @Override
    public PageResult<RoleVO> page(PageQuery pageQuery, String name) {
        Page<Role> page = roleMapper.selectPage(pageQuery.toPage(),
                Wrappers.<Role>lambdaQuery()
                        .like(StringUtils.hasText(name), Role::getName, name)
                        .orderByAsc(Role::getId));
        return PageResult.of(page.convert(this::toVO));
    }

    @Override
    public List<RoleVO> listAll() {
        return roleMapper.selectList(
                        Wrappers.<Role>lambdaQuery().eq(Role::getStatus, 1).orderByAsc(Role::getId))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public void create(RoleSaveRequest request) {
        checkCodeUnique(request.getCode(), null);
        Role role = new Role();
        role.setName(request.getName());
        role.setCode(request.getCode());
        role.setDescription(request.getDescription());
        role.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        roleMapper.insert(role);
    }

    @Override
    public void update(Long id, RoleSaveRequest request) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.ADMIN_ROLE_NOT_FOUND);
        }
        checkCodeUnique(request.getCode(), id);
        role.setName(request.getName());
        role.setCode(request.getCode());
        role.setDescription(request.getDescription());
        if (request.getStatus() != null) {
            role.setStatus(request.getStatus());
        }
        roleMapper.updateById(role);
    }

    @Override
    public void delete(Long id) {
        // 已被管理员绑定的角色禁止删除，避免历史关联悬空
        Long bindCount = adminUserRoleMapper.selectCount(
                Wrappers.<AdminUserRole>lambdaQuery().eq(AdminUserRole::getRoleId, id));
        if (bindCount > 0) {
            throw new BusinessException(ResultCode.ADMIN_ROLE_IN_USE);
        }
        roleMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BusinessException(ResultCode.ADMIN_ROLE_NOT_FOUND);
        }
        // 先删后插，保证幂等
        roleMenuMapper.delete(Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, roleId));
        for (Long menuId : menuIds) {
            RoleMenu roleMenu = new RoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menuId);
            roleMenuMapper.insert(roleMenu);
        }
    }

    @Override
    public List<Long> listMenuIds(Long roleId) {
        return roleMenuMapper.selectList(
                        Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, roleId))
                .stream().map(RoleMenu::getMenuId).collect(Collectors.toList());
    }

    /**
     * 角色编码唯一性校验（修改时排除自身）
     */
    private void checkCodeUnique(String code, Long excludeId) {
        Long count = roleMapper.selectCount(
                Wrappers.<Role>lambdaQuery().eq(Role::getCode, code)
                        .ne(excludeId != null, Role::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ResultCode.ADMIN_ROLE_CODE_EXISTS);
        }
    }

    private RoleVO toVO(Role role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setCode(role.getCode());
        vo.setDescription(role.getDescription());
        vo.setStatus(role.getStatus());
        vo.setCreateTime(role.getCreateTime());
        return vo;
    }
}
