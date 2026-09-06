package com.springshop.admin.config;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台初始数据初始化器
 *
 * <p>首次启动（admin_user / role / menu 均为空）时创建初始数据：
 * <ul>
 *   <li>超级管理员：admin / admin123（密码 BCrypt 加密，上线后务必修改）；</li>
 *   <li>ADMIN 超级角色 + 系统管理基础菜单；</li>
 *   <li>为 admin 绑定 ADMIN 角色并授予全部菜单。</li>
 * </ul>
 * 幂等：表非空时跳过，不会重复插入。
 */
@Component
public class AdminDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminDataInitializer.class);

    private final AdminUserMapper adminUserMapper;
    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final AdminUserRoleMapper adminUserRoleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminDataInitializer(AdminUserMapper adminUserMapper,
                                RoleMapper roleMapper,
                                MenuMapper menuMapper,
                                AdminUserRoleMapper adminUserRoleMapper,
                                RoleMenuMapper roleMenuMapper,
                                PasswordEncoder passwordEncoder) {
        this.adminUserMapper = adminUserMapper;
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.adminUserRoleMapper = adminUserRoleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        if (adminUserMapper.selectCount(null) > 0) {
            return;
        }

        // 1. 创建 ADMIN 角色
        Role adminRole = new Role();
        adminRole.setName("超级管理员");
        adminRole.setCode("ADMIN");
        adminRole.setDescription("拥有系统全部权限");
        adminRole.setStatus(1);
        roleMapper.insert(adminRole);

        // 2. 创建基础菜单树（先插父节点拿到自增 id，再插子节点）
        List<Menu> menus = buildMenus(menuMapper);
        Long systemMenuId = findMenuIdByCode(menus, "system");

        // 3. 创建超级管理员 admin/admin123
        AdminUser admin = new AdminUser();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setRealName("系统管理员");
        admin.setStatus(1);
        adminUserMapper.insert(admin);

        // 4. 绑定 admin → ADMIN 角色 → 全部菜单
        AdminUserRole adminUserRole = new AdminUserRole();
        adminUserRole.setAdminUserId(admin.getId());
        adminUserRole.setRoleId(adminRole.getId());
        adminUserRoleMapper.insert(adminUserRole);

        for (Menu menu : menus) {
            RoleMenu roleMenu = new RoleMenu();
            roleMenu.setRoleId(adminRole.getId());
            roleMenu.setMenuId(menu.getId());
            roleMenuMapper.insert(roleMenu);
        }

        log.info("管理后台初始数据已创建：admin / admin123（请登录后立即修改密码），菜单根 id={}", systemMenuId);
    }

    /**
     * 基础菜单树：系统管理（目录）→ 角色管理 / 菜单管理（菜单）+ 按钮级权限
     */
    private List<Menu> buildMenus(MenuMapper menuMapper) {
        List<Menu> menus = new ArrayList<>();

        Menu system = insertMenu(menuMapper, "系统管理", 1, 0L, "/system", "system", "Setting", 1, menus);
        Menu role = insertMenu(menuMapper, "角色管理", 2, system.getId(), "/system/role", "system:role:list", null, 2, menus);
        Menu menuNode = insertMenu(menuMapper, "菜单管理", 2, system.getId(), "/system/menu", "system:menu:list", null, 3, menus);

        insertMenu(menuMapper, "新增角色", 3, role.getId(), null, "system:role:create", null, 1, menus);
        insertMenu(menuMapper, "修改角色", 3, role.getId(), null, "system:role:update", null, 2, menus);
        insertMenu(menuMapper, "删除角色", 3, role.getId(), null, "system:role:delete", null, 3, menus);
        insertMenu(menuMapper, "分配权限", 3, role.getId(), null, "system:role:assign", null, 4, menus);
        insertMenu(menuMapper, "新增菜单", 3, menuNode.getId(), null, "system:menu:create", null, 1, menus);
        insertMenu(menuMapper, "修改菜单", 3, menuNode.getId(), null, "system:menu:update", null, 2, menus);
        insertMenu(menuMapper, "删除菜单", 3, menuNode.getId(), null, "system:menu:delete", null, 3, menus);

        Menu product = insertMenu(menuMapper, "商品管理", 1, 0L, "/product", "product", "Goods", 2, menus);
        Menu category = insertMenu(menuMapper, "分类管理", 2, product.getId(), "/product/category", "product:category:list", null, 1, menus);
        insertMenu(menuMapper, "新增分类", 3, category.getId(), null, "product:category:create", null, 1, menus);
        insertMenu(menuMapper, "修改分类", 3, category.getId(), null, "product:category:update", null, 2, menus);
        insertMenu(menuMapper, "删除分类", 3, category.getId(), null, "product:category:delete", null, 3, menus);
        Menu productMgmt = insertMenu(menuMapper, "商品管理", 2, product.getId(), "/product/list", "product:product:list", null, 2, menus);
        insertMenu(menuMapper, "新增商品", 3, productMgmt.getId(), null, "product:product:create", null, 1, menus);
        insertMenu(menuMapper, "修改商品", 3, productMgmt.getId(), null, "product:product:update", null, 2, menus);

        return menus;
    }

    /**
     * 构建菜单实体、插入数据库并收集到列表，返回已带自增 id 的实体
     */
    private Menu insertMenu(MenuMapper menuMapper, String name, Integer type, Long parentId,
                            String path, String permissionCode, String icon, Integer sort, List<Menu> menus) {
        Menu menu = new Menu();
        menu.setName(name);
        menu.setType(type);
        menu.setParentId(parentId);
        menu.setPath(path);
        menu.setPermissionCode(permissionCode);
        menu.setIcon(icon);
        menu.setSort(sort);
        menu.setStatus(1);
        menuMapper.insert(menu);
        menus.add(menu);
        return menu;
    }

    /**
     * 从内存菜单列表中按 permission_code 查找 id（列表有序，先插入的子节点已带自增 id）
     */
    private Long findMenuIdByCode(List<Menu> menus, String permissionCode) {
        return menus.stream()
                .filter(m -> permissionCode.equals(m.getPermissionCode()))
                .map(Menu::getId)
                .findFirst()
                .orElse(null);
    }
}
