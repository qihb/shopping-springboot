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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理后台初始数据初始化器
 *
 * <p>启动时保证以下数据存在（全部幂等，可重复执行）：
 * <ul>
 *   <li>超级管理员账号 admin / admin123（密码 BCrypt 加密，上线后务必修改）；</li>
 *   <li>ADMIN 超级角色；</li>
 *   <li>系统管理 / 商品管理 / 订单管理 / 数据运营 四棵菜单树及其按钮级权限；</li>
 *   <li>admin → ADMIN 角色、ADMIN → 全部菜单的关联关系。</li>
 * </ul>
 *
 * <p><b>为什么从「表非空就整体跳过」改成「逐项 find-or-create」</b>：
 * 旧实现一旦库里已有管理员就直接 return，导致后续新增的菜单 / 权限码在老库上永远不会写入，
 * 新接口的 {@code @PreAuthorize} 会因为没有权限记录而一律 403。
 * 改为逐项 ensure 后，新增菜单能在老库上自动补齐，新增按钮权限也能自动授予 ADMIN 角色。
 *
 * <p>副作用说明：ADMIN 是「拥有系统全部权限」的超级角色，每次启动都会把缺失的菜单补授给它。
 * 这是刻意设计——超级角色不该出现权限缺口；需要按最小权限分配时请新建普通角色。
 */
@Component
public class AdminDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminDataInitializer.class);

    /** 超级角色编码 */
    private static final String ADMIN_ROLE_CODE = "ADMIN";

    /** 内置超级管理员用户名 */
    private static final String ADMIN_USERNAME = "admin";

    /** 内置超级管理员初始密码（仅用于首次启动，上线后必须修改） */
    private static final String ADMIN_INIT_PASSWORD = "admin123";

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
        Role adminRole = ensureAdminRole();
        List<Menu> menus = ensureMenus();
        AdminUser admin = ensureAdminUser();
        ensureAdminUserRole(admin.getId(), adminRole.getId());
        int granted = ensureRoleMenus(adminRole.getId(), menus);

        log.info("管理后台初始数据校验完成：账号 {}，ADMIN 角色菜单 {} 项（本次新增授权 {} 项）",
                ADMIN_USERNAME, menus.size(), granted);
    }

    /**
     * 保证 ADMIN 超级角色存在
     */
    private Role ensureAdminRole() {
        Role existing = roleMapper.selectOne(
                Wrappers.<Role>lambdaQuery().eq(Role::getCode, ADMIN_ROLE_CODE));
        if (existing != null) {
            return existing;
        }
        Role role = new Role();
        role.setName("超级管理员");
        role.setCode(ADMIN_ROLE_CODE);
        role.setDescription("拥有系统全部权限");
        role.setStatus(1);
        roleMapper.insert(role);
        return role;
    }

    /**
     * 保证内置超级管理员账号存在
     */
    private AdminUser ensureAdminUser() {
        AdminUser existing = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, ADMIN_USERNAME));
        if (existing != null) {
            return existing;
        }
        AdminUser admin = new AdminUser();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPassword(passwordEncoder.encode(ADMIN_INIT_PASSWORD));
        admin.setRealName("系统管理员");
        admin.setStatus(1);
        adminUserMapper.insert(admin);
        log.info("已创建内置超级管理员 {}（初始密码见初始化器常量，请登录后立即修改）", ADMIN_USERNAME);
        return admin;
    }

    /**
     * 保证管理员与角色的绑定关系存在
     */
    private void ensureAdminUserRole(Long adminUserId, Long roleId) {
        Long count = adminUserRoleMapper.selectCount(Wrappers.<AdminUserRole>lambdaQuery()
                .eq(AdminUserRole::getAdminUserId, adminUserId)
                .eq(AdminUserRole::getRoleId, roleId));
        if (count == null || count == 0) {
            AdminUserRole relation = new AdminUserRole();
            relation.setAdminUserId(adminUserId);
            relation.setRoleId(roleId);
            adminUserRoleMapper.insert(relation);
        }
    }

    /**
     * 保证 ADMIN 角色拥有全部内置菜单（只补缺失项，不删除已有授权）
     *
     * @return 本次新增的授权数量
     */
    private int ensureRoleMenus(Long roleId, List<Menu> menus) {
        Set<Long> boundMenuIds = roleMenuMapper.selectList(
                        Wrappers.<RoleMenu>lambdaQuery().eq(RoleMenu::getRoleId, roleId))
                .stream().map(RoleMenu::getMenuId).collect(Collectors.toCollection(HashSet::new));

        int granted = 0;
        for (Menu menu : menus) {
            if (boundMenuIds.contains(menu.getId())) {
                continue;
            }
            RoleMenu roleMenu = new RoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menu.getId());
            roleMenuMapper.insert(roleMenu);
            granted++;
        }
        return granted;
    }

    /**
     * 内置菜单树：系统管理（用户/角色/菜单/操作日志）、商品管理（分类/商品）、
     * 订单管理（订单列表）、数据运营（加购未买召回），每棵树下含按钮级权限
     */
    private List<Menu> ensureMenus() {
        List<Menu> menus = new ArrayList<>();

        Menu system = ensureMenu("系统管理", 1, 0L, "/system", "system", "Setting", 1, menus);
        Menu user = ensureMenu("用户管理", 2, system.getId(), "/system/user", "system:user:list", null, 1, menus);
        Menu role = ensureMenu("角色管理", 2, system.getId(), "/system/role", "system:role:list", null, 2, menus);
        Menu menuNode = ensureMenu("菜单管理", 2, system.getId(), "/system/menu", "system:menu:list", null, 3, menus);
        ensureMenu("操作日志", 2, system.getId(), "/system/log", "system:log:list", null, 4, menus);

        ensureMenu("新增管理员", 3, user.getId(), null, "system:user:create", null, 1, menus);
        ensureMenu("修改管理员", 3, user.getId(), null, "system:user:update", null, 2, menus);
        ensureMenu("重置密码", 3, user.getId(), null, "system:user:reset", null, 3, menus);
        ensureMenu("分配角色", 3, user.getId(), null, "system:user:assign", null, 4, menus);
        ensureMenu("导入管理员", 3, user.getId(), null, "system:user:import", null, 5, menus);

        ensureMenu("新增角色", 3, role.getId(), null, "system:role:create", null, 1, menus);
        ensureMenu("修改角色", 3, role.getId(), null, "system:role:update", null, 2, menus);
        ensureMenu("删除角色", 3, role.getId(), null, "system:role:delete", null, 3, menus);
        ensureMenu("分配权限", 3, role.getId(), null, "system:role:assign", null, 4, menus);

        ensureMenu("新增菜单", 3, menuNode.getId(), null, "system:menu:create", null, 1, menus);
        ensureMenu("修改菜单", 3, menuNode.getId(), null, "system:menu:update", null, 2, menus);
        ensureMenu("删除菜单", 3, menuNode.getId(), null, "system:menu:delete", null, 3, menus);

        Menu product = ensureMenu("商品管理", 1, 0L, "/product", "product", "Goods", 2, menus);
        Menu category = ensureMenu("分类管理", 2, product.getId(), "/product/category", "product:category:list", null, 1, menus);
        ensureMenu("新增分类", 3, category.getId(), null, "product:category:create", null, 1, menus);
        ensureMenu("修改分类", 3, category.getId(), null, "product:category:update", null, 2, menus);
        ensureMenu("删除分类", 3, category.getId(), null, "product:category:delete", null, 3, menus);
        Menu productMgmt = ensureMenu("商品管理", 2, product.getId(), "/product/list", "product:product:list", null, 2, menus);
        ensureMenu("新增商品", 3, productMgmt.getId(), null, "product:product:create", null, 1, menus);
        ensureMenu("修改商品", 3, productMgmt.getId(), null, "product:product:update", null, 2, menus);
        ensureMenu("导入商品", 3, productMgmt.getId(), null, "product:product:import", null, 3, menus);
        ensureMenu("删除商品", 3, productMgmt.getId(), null, "product:product:delete", null, 4, menus);
        Menu inventory = ensureMenu("库存管理", 2, product.getId(), "/product/inventory", "product:inventory:list", null, 3, menus);
        ensureMenu("调整库存", 3, inventory.getId(), null, "product:inventory:adjust", null, 1, menus);
        Menu brand = ensureMenu("品牌管理", 2, product.getId(), "/product/brand", "product:brand:list", null, 4, menus);
        ensureMenu("新增品牌", 3, brand.getId(), null, "product:brand:create", null, 1, menus);
        ensureMenu("修改品牌", 3, brand.getId(), null, "product:brand:update", null, 2, menus);
        ensureMenu("删除品牌", 3, brand.getId(), null, "product:brand:delete", null, 3, menus);
        Menu attribute = ensureMenu("属性管理", 2, product.getId(), "/product/attribute", "product:attribute:list", null, 5, menus);
        ensureMenu("新增属性", 3, attribute.getId(), null, "product:attribute:create", null, 1, menus);
        ensureMenu("修改属性", 3, attribute.getId(), null, "product:attribute:update", null, 2, menus);
        ensureMenu("删除属性", 3, attribute.getId(), null, "product:attribute:delete", null, 3, menus);

        Menu order = ensureMenu("订单管理", 1, 0L, "/order", "order", "List", 3, menus);
        Menu orderList = ensureMenu("订单列表", 2, order.getId(), "/order/list", "order:order:list", null, 1, menus);
        ensureMenu("订单发货", 3, orderList.getId(), null, "order:order:ship", null, 1, menus);

        Menu stats = ensureMenu("数据运营", 1, 0L, "/stats", "stats", "DataAnalysis", 4, menus);
        Menu recall = ensureMenu("加购未买召回", 2, stats.getId(), "/stats/recall", "stats:recall:list", null, 1, menus);
        ensureMenu("执行圈人", 3, recall.getId(), null, "stats:recall:build", null, 1, menus);
        // 任务执行日志与召回并列（不是它的子菜单）：这张表将来还会承载订单超时、
        // Excel 清理等任务的执行记录，挂在 recall 下语义就错了
        ensureMenu("任务执行日志", 2, stats.getId(), "/stats/task-logs", "stats:task-log:list", null, 2, menus);

        return menus;
    }

    /**
     * 按 permission_code 查找菜单，不存在则创建
     *
     * <p>permission_code 在内置菜单里全局唯一，因此可以作为幂等键；
     * 同时把「已存在」的菜单收集进列表，供后续统一授权给 ADMIN 角色。
     *
     * @param parentId       父菜单 id，顶级传 0
     * @param permissionCode 权限标识，同时作为幂等键
     */
    private Menu ensureMenu(String name, Integer type, Long parentId,
                            String path, String permissionCode, String icon, Integer sort,
                            List<Menu> collected) {
        Menu existing = menuMapper.selectOne(
                Wrappers.<Menu>lambdaQuery().eq(Menu::getPermissionCode, permissionCode));
        if (existing != null) {
            collected.add(existing);
            return existing;
        }

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
        collected.add(menu);
        return menu;
    }
}
