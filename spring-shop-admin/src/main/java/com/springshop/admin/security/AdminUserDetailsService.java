package com.springshop.admin.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Menu;
import com.springshop.admin.entity.Role;
import com.springshop.admin.entity.RoleMenu;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.MenuMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.admin.mapper.RoleMenuMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员用户加载服务：按用户名查库，并组装其角色对应的权限标识集合
 */
@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserMapper adminUserMapper;
    private final AdminUserRoleMapper adminUserRoleMapper;
    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuMapper menuMapper;

    public AdminUserDetailsService(AdminUserMapper adminUserMapper,
                                   AdminUserRoleMapper adminUserRoleMapper,
                                   RoleMapper roleMapper,
                                   RoleMenuMapper roleMenuMapper,
                                   MenuMapper menuMapper) {
        this.adminUserMapper = adminUserMapper;
        this.adminUserRoleMapper = adminUserRoleMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AdminUser adminUser = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, username));
        if (adminUser == null) {
            throw new UsernameNotFoundException("管理员不存在: " + username);
        }
        return new AdminUserPrincipal(adminUser, loadPermissions(adminUser.getId()));
    }

    /**
     * 加载管理员启用中的角色编码集合，如 ["ADMIN"]
     */
    public List<String> loadRoles(Long adminUserId) {
        List<Long> roleIds = adminUserRoleMapper.selectList(
                        Wrappers.<AdminUserRole>lambdaQuery().eq(AdminUserRole::getAdminUserId, adminUserId))
                .stream().map(AdminUserRole::getRoleId).collect(Collectors.toList());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(
                        Wrappers.<Role>lambdaQuery().in(Role::getId, roleIds).eq(Role::getStatus, 1))
                .stream().map(Role::getCode).collect(Collectors.toList());
    }

    /**
     * 加载管理员全部权限标识（角色 → 菜单的 permission_code）
     */
    public Set<String> loadPermissions(Long adminUserId) {
        List<Long> roleIds = adminUserRoleMapper.selectList(
                        Wrappers.<AdminUserRole>lambdaQuery().eq(AdminUserRole::getAdminUserId, adminUserId))
                .stream().map(AdminUserRole::getRoleId).collect(Collectors.toList());
        if (roleIds.isEmpty()) {
            return Set.of();
        }

        // 仅统计启用中的角色
        List<Long> enabledRoleIds = roleMapper.selectList(
                        Wrappers.<Role>lambdaQuery().in(Role::getId, roleIds).eq(Role::getStatus, 1))
                .stream().map(Role::getId).collect(Collectors.toList());
        if (enabledRoleIds.isEmpty()) {
            return Set.of();
        }

        List<Long> menuIds = roleMenuMapper.selectList(
                        Wrappers.<RoleMenu>lambdaQuery().in(RoleMenu::getRoleId, enabledRoleIds))
                .stream().map(RoleMenu::getMenuId).collect(Collectors.toList());
        if (menuIds.isEmpty()) {
            return Set.of();
        }

        return menuMapper.selectList(
                        Wrappers.<Menu>lambdaQuery().in(Menu::getId, menuIds).eq(Menu::getStatus, 1))
                .stream()
                .map(Menu::getPermissionCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
    }
}
