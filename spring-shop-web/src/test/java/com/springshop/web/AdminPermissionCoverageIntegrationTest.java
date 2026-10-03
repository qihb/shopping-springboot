package com.springshop.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接口权限码覆盖率集成测试 —— 把「新增后台接口时忘了注册权限」这个坑变成 CI 红灯。
 *
 * <p>背景：后台接口的鉴权靠 {@code @PreAuthorize("hasAuthority('xxx')")}，而 authority
 * 的来源是 {@code menu.permission_code}，由 {@code AdminDataInitializer} 幂等注入。
 * 一旦接口上写了权限码、但初始数据里没有这条记录，调用方会**永久 403**，
 * 而且现象极难定位（接口、代码、token 都正常，就是没权限）。
 *
 * <p>本测试不依赖初始化器的实现细节，而是直接从**运行时容器**里把真实生效的
 * {@code @PreAuthorize} 表达式扫出来，再和数据库里的初始数据对账：
 *
 * <ol>
 *   <li>{@link #everyPreAuthorizeAuthority_shouldBeRegisteredInMenuTable}
 *       —— 每个权限码都必须在 {@code menu} 表里注册且启用；</li>
 *   <li>{@link #everyPreAuthorizeAuthority_shouldBeGrantedToAdminRole}
 *       —— 每个权限码都必须已授予 {@code ADMIN} 角色（否则超管自己也进不去）；</li>
 *   <li>{@link #authorityScan_shouldActuallyFindAnnotations}
 *       —— 兜底断言扫描真的扫到了东西，避免反射失效导致本测试「空转全绿」。</li>
 * </ol>
 *
 * <p>⚠️ 如果将来出现 {@code hasAuthority} 之外的写法（如 {@code hasAnyAuthority}、{@code hasRole}），
 * 解析会失败并**直接让用例失败**，而不是被静默跳过 —— 这时需要同步更新本测试的解析逻辑。
 *
 * <p><b>唯一的白名单：{@code isAuthenticated()}</b>。{@link com.springshop.web.controller.ExcelTaskController}
 * （Excel 任务中心）刻意不用权限码：能提交导入/导出任务说明提交时已经过了权限码校验，任务本身用
 * {@code created_by} 做归属校验，只能查自己的任务。这类接口不属于「权限码体系」，
 * 因此不参与本测试的对账 —— 但白名单是**精确匹配整个表达式**的，
 * 写成 {@code isAuthenticated() and hasAuthority('x')} 依然会被判为不可解析。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminPermissionCoverageIntegrationTest {

    /** 从 SpEL 表达式里提取权限码，兼容单引号与双引号写法 */
    private static final Pattern AUTHORITY_PATTERN =
            Pattern.compile("hasAuthority\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    /**
     * 白名单：只要求登录、不要求权限码的表达式。
     *
     * <p>刻意用 {@code matches()} 做**整串精确匹配**而不是 {@code find()}：
     * 只有表达式「就是」{@code isAuthenticated()} 时才豁免。
     * 一旦写成 {@code isAuthenticated() and hasAuthority('x')} 这类复合表达式，
     * 就不再走白名单，而是回到正常的权限码提取流程。
     */
    private static final Pattern AUTHENTICATED_ONLY_PATTERN =
            Pattern.compile("\\s*isAuthenticated\\(\\)\\s*");

    private static final String ADMIN_ROLE_CODE = "ADMIN";

    /** 扫描结果的兜底下限：当前有 28 个权限码，留出余量 */
    private static final int MIN_EXPECTED_AUTHORITIES = 20;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /**
     * 每个接口上声明的权限码，都必须在 {@code menu} 表里注册且处于启用状态。
     */
    @Test
    void everyPreAuthorizeAuthority_shouldBeRegisteredInMenuTable() {
        Map<String, List<String>> authorities = collectAuthoritiesFromControllers();

        Set<String> registered = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT permission_code FROM menu "
                        + "WHERE permission_code IS NOT NULL AND is_deleted = 0 AND status = 1",
                String.class));

        List<String> missing = authorities.keySet().stream()
                .filter(code -> !registered.contains(code))
                .sorted()
                .toList();

        assertTrue(missing.isEmpty(), () -> """
                以下接口权限码没有在 menu 表注册（调用方会永久 403），
                请在 AdminDataInitializer 的菜单定义里补上，并同步扩充初始数据测试：
                """ + describe(missing, authorities));
    }

    /**
     * 每个接口上声明的权限码，都必须已经授予 {@code ADMIN} 角色。
     *
     * <p>只注册不授权同样是 403 —— 初始数据「只增不减」地给 ADMIN 补齐全部菜单，
     * 所以这里不应该有例外。
     */
    @Test
    void everyPreAuthorizeAuthority_shouldBeGrantedToAdminRole() {
        Map<String, List<String>> authorities = collectAuthoritiesFromControllers();

        Set<String> granted = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT m.permission_code FROM menu m "
                        + "JOIN role_menu rm ON rm.menu_id = m.id "
                        + "JOIN role r ON r.id = rm.role_id "
                        + "WHERE r.code = ? AND m.permission_code IS NOT NULL AND m.is_deleted = 0",
                String.class, ADMIN_ROLE_CODE));

        List<String> ungranted = authorities.keySet().stream()
                .filter(code -> !granted.contains(code))
                .sorted()
                .toList();

        assertTrue(ungranted.isEmpty(), () -> """
                以下接口权限码已注册但没有授予 ADMIN 角色（超级管理员会拿不到权限），
                请检查 AdminDataInitializer 的角色-菜单授权：
                """ + describe(ungranted, authorities));
    }

    /**
     * 兜底断言：扫描逻辑必须真的扫到注解，否则前两个用例会因为集合为空而「假绿」。
     */
    @Test
    void authorityScan_shouldActuallyFindAnnotations() {
        Map<String, List<String>> authorities = collectAuthoritiesFromControllers();

        assertFalse(authorities.isEmpty(), "没有扫描到任何 @PreAuthorize 权限码，反射逻辑可能已失效");
        assertTrue(authorities.size() >= MIN_EXPECTED_AUTHORITIES,
                () -> "只扫描到 " + authorities.size() + " 个权限码，少于预期下限 "
                        + MIN_EXPECTED_AUTHORITIES + "，反射逻辑可能已失效：" + authorities.keySet());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /**
     * 扫描容器里全部 {@code @RestController}，收集 {@code @PreAuthorize} 中声明的权限码。
     *
     * @return 权限码 -> 声明位置（类#方法）列表，便于失败时定位
     */
    private Map<String, List<String>> collectAuthoritiesFromControllers() {
        Map<String, List<String>> authorities = new TreeMap<>();
        List<String> unparseable = new ArrayList<>();

        for (Object bean : applicationContext.getBeansWithAnnotation(RestController.class).values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            String classLocation = targetClass.getSimpleName();

            // 类级注解
            PreAuthorize classLevel = AnnotationUtils.findAnnotation(targetClass, PreAuthorize.class);
            if (classLevel != null) {
                collect(classLevel, classLocation, authorities, unparseable);
            }

            // 方法级注解（用 getAllDeclaredMethods 以覆盖父类声明的方法）
            Method[] methods = ReflectionUtils.getAllDeclaredMethods(targetClass);
            for (Method method : methods) {
                PreAuthorize methodLevel = AnnotationUtils.findAnnotation(method, PreAuthorize.class);
                if (methodLevel != null) {
                    collect(methodLevel, classLocation + "#" + method.getName(), authorities, unparseable);
                }
            }
        }

        assertTrue(unparseable.isEmpty(), () -> """
                存在无法解析的 @PreAuthorize 表达式（本测试的解析规则只认识 hasAuthority('...')），
                请同步更新 AdminPermissionCoverageIntegrationTest 的解析逻辑，不要让它静默跳过：
                """ + String.join("\n", unparseable));

        return authorities;
    }

    private void collect(PreAuthorize preAuthorize, String location,
                         Map<String, List<String>> authorities, List<String> unparseable) {
        String expression = preAuthorize.value();
        if (AUTHENTICATED_ONLY_PATTERN.matcher(expression).matches()) {
            // 「登录即可」的接口（Excel 任务中心）走归属校验而不是权限码，不属于本测试的对账范围
            return;
        }
        Matcher matcher = AUTHORITY_PATTERN.matcher(expression);
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            authorities.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(location);
        }
        if (!matched) {
            unparseable.add("  - " + location + " -> " + expression);
        }
    }

    private String describe(List<String> codes, Map<String, List<String>> authorities) {
        return codes.stream()
                .map(code -> "  - " + code + "  用于 " + authorities.get(code))
                .collect(Collectors.joining("\n"));
    }
}
