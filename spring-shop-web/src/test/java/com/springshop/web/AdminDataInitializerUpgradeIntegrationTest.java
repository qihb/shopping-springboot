package com.springshop.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.admin.config.AdminDataInitializer;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.Menu;
import com.springshop.admin.entity.Role;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.MenuMapper;
import com.springshop.admin.mapper.RoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 初始数据「升级路径」集成测试 —— 验证 {@code AdminDataInitializer} 在**已有数据的库**上再次启动时的行为。
 *
 * <p>为什么需要这个测试：最初的实现是「{@code admin_user} 表非空就整体跳过」，
 * 导致**后续版本新增的菜单 / 按钮权限永远写不进老库**，新接口的
 * {@code @PreAuthorize("hasAuthority('...')")} 会永久 403，且现象极难定位。
 * 现在的实现改为以 {@code menu.permission_code} 为幂等键逐项补齐。
 * 这个测试就是钉住这个契约：**新权限必须能自动下发到老库，且重复启动不产生重复数据**。
 *
 * <p>测试手法：
 * <ul>
 *   <li>用原生 SQL <b>物理删除</b>初始数据，模拟「老库由旧版本初始化、根本没有这条记录」，
 *       而不是逻辑删除 —— 逻辑删除的行仍在表里，模拟不出真实升级场景。</li>
 *   <li>删除后手动再跑一次 {@code ApplicationRunner#run}，断言缺失部分被补齐。</li>
 * </ul>
 *
 * <p>注意 {@code @Transactional} 让每个用例结束后自动回滚，用例之间互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminDataInitializerUpgradeIntegrationTest {

    /** 探针权限：由初始数据注入，且 {@code AdminIntegrationTest} 已断言其存在 */
    private static final String PROBE_PERMISSION = "system:role:list";

    /** 初始数据里的超级管理员角色编码 */
    private static final String ADMIN_ROLE_CODE = "ADMIN";

    /** 初始数据里的超级管理员用户名 */
    private static final String ADMIN_USERNAME = "admin";

    @Autowired
    private AdminDataInitializer initializer;

    @Autowired
    private MenuMapper menuMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private AdminUserMapper adminUserMapper;

    @Autowired
    private DataSource dataSource;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 测试环境无 Redis：mock 掉 opsForValue()，与其他集成测试保持一致
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /**
     * 核心用例：老库缺少某个按钮权限（连同授权）时，重新启动应自动补齐，且不产生重复记录。
     */
    @Test
    void deletedPermission_shouldBeRestoredOnNextStartup() throws Exception {
        Menu before = requireProbeMenu();
        Long roleId = requireAdminRole().getId();
        Long staleMenuId = before.getId();

        // 模拟老库：这条权限及其授权在库里根本不存在
        jdbcTemplate.update("DELETE FROM role_menu WHERE menu_id = ?", staleMenuId);
        jdbcTemplate.update("DELETE FROM menu WHERE id = ?", staleMenuId);
        assertEquals(0L, countMenuByPermission(PROBE_PERMISSION), "前置条件：探针权限应已被物理删除");

        runInitializer();

        // 补齐后重新查询：新插入的行 id 是自增新值，不能用删除前的 id
        Menu after = requireProbeMenu();
        assertEquals(1L, countMenuByPermission(PROBE_PERMISSION),
                "同一 permission_code 应只补一条，重复插入会污染权限表");
        assertEquals(1L, countRoleMenu(roleId, after.getId()),
                "ADMIN 角色应重新获得该权限，且不产生重复授权");
    }

    /**
     * 菜单本身存在、只缺授权关联时，也应补齐（{@code ensureRoleMenus} 只增不减）。
     */
    @Test
    void missingRoleMenuGrant_shouldBeRestoredOnNextStartup() throws Exception {
        Menu probe = requireProbeMenu();
        Long roleId = requireAdminRole().getId();
        Long menuId = probe.getId();

        jdbcTemplate.update("DELETE FROM role_menu WHERE role_id = ? AND menu_id = ?", roleId, menuId);
        assertEquals(0L, countRoleMenu(roleId, menuId), "前置条件：授权关联应已被删除");

        runInitializer();

        assertEquals(1L, countRoleMenu(roleId, menuId), "缺失的授权应被补齐");
        assertEquals(1L, countMenuByPermission(PROBE_PERMISSION), "菜单本身已存在，不应被重复插入");
    }

    /**
     * 幂等性：在已初始化完成的库上重复启动，各类初始数据的行数都不能变。
     */
    @Test
    void repeatedStartup_shouldNotDuplicateSeedData() throws Exception {
        long menus = countAll("menu");
        long roles = countAll("role");
        long roleMenus = countAll("role_menu");
        long adminUsers = countAll("admin_user");
        long adminUserRoles = countAll("admin_user_role");

        runInitializer();
        runInitializer();

        assertEquals(menus, countAll("menu"), "重复启动不应重复插入菜单");
        assertEquals(roles, countAll("role"), "重复启动不应重复插入角色");
        assertEquals(roleMenus, countAll("role_menu"), "重复启动不应重复插入角色-菜单授权");
        assertEquals(adminUsers, countAll("admin_user"), "重复启动不应重复创建管理员");
        assertEquals(adminUserRoles, countAll("admin_user_role"), "重复启动不应重复绑定管理员角色");
    }

    /**
     * 管理员与超级管理员角色的绑定缺失时，也应被补齐。
     */
    @Test
    void missingAdminRoleBinding_shouldBeRestoredOnNextStartup() throws Exception {
        Long adminUserId = requireAdminUser().getId();
        Long roleId = requireAdminRole().getId();

        jdbcTemplate.update("DELETE FROM admin_user_role WHERE admin_user_id = ? AND role_id = ?",
                adminUserId, roleId);
        assertEquals(0L, countAdminUserRole(adminUserId, roleId), "前置条件：角色绑定应已被删除");

        runInitializer();

        assertEquals(1L, countAdminUserRole(adminUserId, roleId), "admin 的超级管理员角色绑定应被补齐");
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 再跑一次启动初始化，等价于「应用重新启动」 */
    private void runInitializer() throws Exception {
        initializer.run(new DefaultApplicationArguments(new String[0]));
    }

    private Menu requireProbeMenu() {
        Menu menu = menuMapper.selectOne(Wrappers.<Menu>lambdaQuery()
                .eq(Menu::getPermissionCode, PROBE_PERMISSION)
                .last("LIMIT 1"));
        assertNotNull(menu, "初始数据应包含权限 " + PROBE_PERMISSION);
        return menu;
    }

    private Role requireAdminRole() {
        Role role = roleMapper.selectOne(Wrappers.<Role>lambdaQuery()
                .eq(Role::getCode, ADMIN_ROLE_CODE)
                .last("LIMIT 1"));
        assertNotNull(role, "初始数据应包含角色 " + ADMIN_ROLE_CODE);
        return role;
    }

    private AdminUser requireAdminUser() {
        AdminUser adminUser = adminUserMapper.selectOne(Wrappers.<AdminUser>lambdaQuery()
                .eq(AdminUser::getUsername, ADMIN_USERNAME)
                .last("LIMIT 1"));
        assertNotNull(adminUser, "初始数据应包含管理员 " + ADMIN_USERNAME);
        return adminUser;
    }

    /** 统计某权限标识对应的菜单条数（MyBatis-Plus 会自动过滤逻辑删除行） */
    private long countMenuByPermission(String permissionCode) {
        return menuMapper.selectCount(Wrappers.<Menu>lambdaQuery()
                .eq(Menu::getPermissionCode, permissionCode));
    }

    private long countRoleMenu(Long roleId, Long menuId) {
        return queryCount("SELECT COUNT(*) FROM role_menu WHERE role_id = ? AND menu_id = ?", roleId, menuId);
    }

    private long countAdminUserRole(Long adminUserId, Long roleId) {
        return queryCount("SELECT COUNT(*) FROM admin_user_role WHERE admin_user_id = ? AND role_id = ?",
                adminUserId, roleId);
    }

    /** 原始表行数（含逻辑删除行），用于幂等性断言 */
    private long countAll(String table) {
        return queryCount("SELECT COUNT(*) FROM " + table);
    }

    private long queryCount(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }
}
