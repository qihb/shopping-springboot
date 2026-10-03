package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.admin.dto.AdminUserCreateRequest;
import com.springshop.admin.dto.AdminUserPageQuery;
import com.springshop.admin.dto.AdminUserUpdateRequest;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Role;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.admin.vo.AdminUserVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    @Mock
    private AdminUserMapper adminUserMapper;

    @Mock
    private AdminUserRoleMapper adminUserRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminUserServiceImpl adminUserService;

    /**
     * 预热 MyBatis-Plus 的 Lambda 元数据（沿用 product / cart / order 模块单测的既有做法）
     *
     * <p>{@code LambdaQueryWrapper} 的 {@code in(...)} 会在打桩前就解析列名，
     * 缺少 TableInfo 时直接抛「can not find lambda cache for this entity」，
     * 因此必须在纯 Mockito 环境下手动初始化。
     */
    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] { AdminUser.class, AdminUserRole.class, Role.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    @Test
    void create_should_fail_when_username_exists() {
        when(adminUserMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.create(buildCreateRequest()));

        assertEquals(ResultCode.ADMIN_USERNAME_EXISTS.getCode(), ex.getCode());
        verify(adminUserMapper, never()).insert(any(AdminUser.class));
    }

    @Test
    void create_should_fail_when_role_not_found() {
        when(adminUserMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        // 请求里给了 1 个角色，但库里只匹配到 0 个 → 视为角色不存在
        when(roleMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.create(buildCreateRequest()));

        assertEquals(ResultCode.ADMIN_ROLE_NOT_FOUND.getCode(), ex.getCode());
        verify(adminUserMapper, never()).insert(any(AdminUser.class));
    }

    @Test
    void create_should_encode_password_and_bind_roles() {
        when(adminUserMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(roleMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(passwordEncoder.encode("123456")).thenReturn("ENCODED");
        when(adminUserMapper.insert(any(AdminUser.class))).thenAnswer(invocation -> {
            AdminUser user = invocation.getArgument(0);
            user.setId(7L);
            return 1;
        });

        Long id = adminUserService.create(buildCreateRequest());

        assertEquals(7L, id);
        ArgumentCaptor<AdminUser> userCaptor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).insert(userCaptor.capture());
        // 明文密码绝不能落库
        assertEquals("ENCODED", userCaptor.getValue().getPassword());
        assertEquals("operator", userCaptor.getValue().getUsername());
        verify(adminUserRoleMapper).insert(any(AdminUserRole.class));
    }

    @Test
    void create_should_default_status_to_enabled() {
        when(adminUserMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode("123456")).thenReturn("ENCODED");
        when(adminUserMapper.insert(any(AdminUser.class))).thenAnswer(invocation -> {
            AdminUser user = invocation.getArgument(0);
            user.setId(7L);
            return 1;
        });
        AdminUserCreateRequest request = buildCreateRequest();
        request.setStatus(null);
        request.setRoleIds(List.of());

        adminUserService.create(request);

        ArgumentCaptor<AdminUser> userCaptor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).insert(userCaptor.capture());
        assertEquals(1, userCaptor.getValue().getStatus());
        verify(adminUserRoleMapper, never()).insert(any(AdminUserRole.class));
    }

    @Test
    void updateStatus_should_reject_disabling_self() {
        when(adminUserMapper.selectById(1L)).thenReturn(buildAdminUser(1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.updateStatus(1L, 0, 1L));

        assertEquals(ResultCode.ADMIN_SELF_OPERATION_FORBIDDEN.getCode(), ex.getCode());
        verify(adminUserMapper, never()).updateById(any(AdminUser.class));
    }

    @Test
    void updateStatus_should_reject_invalid_status_value() {
        when(adminUserMapper.selectById(1L)).thenReturn(buildAdminUser(1L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.updateStatus(1L, 5, 99L));

        assertEquals(ResultCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(adminUserMapper, never()).updateById(any(AdminUser.class));
    }

    @Test
    void updateStatus_should_allow_disabling_other_admin() {
        when(adminUserMapper.selectById(2L)).thenReturn(buildAdminUser(2L));

        adminUserService.updateStatus(2L, 0, 1L);

        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void update_should_fail_when_admin_not_found() {
        when(adminUserMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.update(99L, new AdminUserUpdateRequest(), 1L));

        assertEquals(ResultCode.ADMIN_USER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void update_should_reject_disabling_self_via_update() {
        when(adminUserMapper.selectById(1L)).thenReturn(buildAdminUser(1L));
        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setStatus(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminUserService.update(1L, request, 1L));

        assertEquals(ResultCode.ADMIN_SELF_OPERATION_FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void resetPassword_should_encode_new_password() {
        when(adminUserMapper.selectById(3L)).thenReturn(buildAdminUser(3L));
        when(passwordEncoder.encode("newpass123")).thenReturn("ENCODED2");

        adminUserService.resetPassword(3L, "newpass123");

        ArgumentCaptor<AdminUser> captor = ArgumentCaptor.forClass(AdminUser.class);
        verify(adminUserMapper).updateById(captor.capture());
        assertEquals(3L, captor.getValue().getId());
        assertEquals("ENCODED2", captor.getValue().getPassword());
    }

    @Test
    void assignRoles_should_replace_existing_relations() {
        when(adminUserMapper.selectById(3L)).thenReturn(buildAdminUser(3L));
        when(roleMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        adminUserService.assignRoles(3L, List.of(1L, 2L));

        // 先删后插，保证幂等
        verify(adminUserRoleMapper).delete(any(LambdaQueryWrapper.class));
        verify(adminUserRoleMapper, times(2)).insert(any(AdminUserRole.class));
    }

    @Test
    void assignRoles_should_clear_relations_when_role_ids_empty() {
        when(adminUserMapper.selectById(3L)).thenReturn(buildAdminUser(3L));

        adminUserService.assignRoles(3L, List.of());

        verify(adminUserRoleMapper).delete(any(LambdaQueryWrapper.class));
        verify(adminUserRoleMapper, never()).insert(any(AdminUserRole.class));
    }

    @Test
    void page_should_fill_role_names_without_per_row_query() {
        Page<AdminUser> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(buildAdminUser(1L)));

        when(adminUserMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(adminUserRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(buildRelation(1L, 2L)));
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildRole(2L, "超级管理员")));

        PageResult<AdminUserVO> result = adminUserService.page(new AdminUserPageQuery());

        assertEquals(1, result.getRecords().size());
        assertEquals(List.of("超级管理员"), result.getRecords().get(0).getRoleNames());
        assertEquals(List.of(2L), result.getRecords().get(0).getRoleIds());
    }

    @Test
    void page_should_return_empty_when_no_records() {
        Page<AdminUser> page = new Page<>(1, 10, 0);
        page.setRecords(List.of());
        when(adminUserMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        PageResult<AdminUserVO> result = adminUserService.page(new AdminUserPageQuery());

        assertTrue(result.getRecords().isEmpty());
        assertEquals(0L, result.getTotal());
    }

    private AdminUserCreateRequest buildCreateRequest() {
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setUsername("operator");
        request.setPassword("123456");
        request.setRealName("张运营");
        request.setRoleIds(List.of(2L));
        return request;
    }

    private AdminUser buildAdminUser(Long id) {
        AdminUser user = new AdminUser();
        user.setId(id);
        user.setUsername("admin" + id);
        user.setStatus(1);
        return user;
    }

    private AdminUserRole buildRelation(Long adminUserId, Long roleId) {
        AdminUserRole relation = new AdminUserRole();
        relation.setAdminUserId(adminUserId);
        relation.setRoleId(roleId);
        return relation;
    }

    private Role buildRole(Long id, String name) {
        Role role = new Role();
        role.setId(id);
        role.setName(name);
        return role;
    }
}
