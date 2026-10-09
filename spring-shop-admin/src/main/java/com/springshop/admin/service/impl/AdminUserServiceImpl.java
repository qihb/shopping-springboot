package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.admin.dto.AdminUserCreateRequest;
import com.springshop.admin.dto.AdminUserExportQuery;
import com.springshop.admin.dto.AdminUserPageQuery;
import com.springshop.admin.dto.AdminUserUpdateRequest;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.entity.AdminUserRole;
import com.springshop.admin.entity.Role;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.mapper.AdminUserRoleMapper;
import com.springshop.admin.mapper.RoleMapper;
import com.springshop.admin.service.AdminUserService;
import com.springshop.admin.vo.AdminUserVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员账号管理服务实现
 */
@Service
public class AdminUserServiceImpl implements AdminUserService {

    /** 合法状态值：启用 / 禁用 */
    private static final int STATUS_ENABLED = 1;

    private static final int STATUS_DISABLED = 0;

    private final AdminUserMapper adminUserMapper;
    private final AdminUserRoleMapper adminUserRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminUserServiceImpl(AdminUserMapper adminUserMapper,
                                AdminUserRoleMapper adminUserRoleMapper,
                                RoleMapper roleMapper,
                                PasswordEncoder passwordEncoder) {
        this.adminUserMapper = adminUserMapper;
        this.adminUserRoleMapper = adminUserRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public PageResult<AdminUserVO> page(AdminUserPageQuery query) {
        Page<AdminUser> page = adminUserMapper.selectPage(query.toPage(),
                Wrappers.<AdminUser>lambdaQuery()
                        .like(StringUtils.hasText(query.getUsername()), AdminUser::getUsername, query.getUsername())
                        .like(StringUtils.hasText(query.getRealName()), AdminUser::getRealName, query.getRealName())
                        .eq(query.getStatus() != null, AdminUser::getStatus, query.getStatus())
                        .orderByAsc(AdminUser::getId));

        Page<AdminUserVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(toVOList(page.getRecords()));
        return PageResult.of(voPage);
    }

    @Override
    public List<AdminUserVO> exportPage(AdminUserExportQuery query, Long lastId, long pageSize) {
        LambdaQueryWrapper<AdminUser> wrapper = Wrappers.lambdaQuery();
        if (query != null) {
            if (query.getIds() != null && !query.getIds().isEmpty()) {
                // 「导出选中」优先于筛选条件
                wrapper.in(AdminUser::getId, query.getIds());
            } else {
                wrapper.like(StringUtils.hasText(query.getUsername()), AdminUser::getUsername, query.getUsername())
                        .like(StringUtils.hasText(query.getRealName()), AdminUser::getRealName, query.getRealName())
                        .eq(query.getStatus() != null, AdminUser::getStatus, query.getStatus());
            }
        }
        // keyset 游标：正序导出，所以取「比上一页最后一条更大」的 id。
        // 不用 OFFSET 是因为导出要跑几分钟，期间新增管理员会让后续页窗口整体后移
        wrapper.gt(lastId != null, AdminUser::getId, lastId);
        wrapper.orderByAsc(AdminUser::getId);
        // 游标分页每页都取第一页；searchCount=false：导出不展示总页数，省掉每页一次 COUNT
        Page<AdminUser> page = adminUserMapper.selectPage(new Page<>(1, pageSize, false), wrapper);
        return toVOList(page.getRecords());
    }

    @Override
    public AdminUserVO detail(Long id) {
        return toVOList(List.of(requireAdminUser(id))).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AdminUserCreateRequest request) {
        checkUsernameUnique(request.getUsername());
        validateRolesExist(request.getRoleIds());

        AdminUser adminUser = new AdminUser();
        adminUser.setUsername(request.getUsername());
        // 密码绝不落库明文，BCrypt 自动加盐
        adminUser.setPassword(passwordEncoder.encode(request.getPassword()));
        adminUser.setRealName(request.getRealName());
        adminUser.setPhone(request.getPhone());
        adminUser.setStatus(request.getStatus() == null ? STATUS_ENABLED : request.getStatus());
        adminUserMapper.insert(adminUser);

        replaceRoles(adminUser.getId(), request.getRoleIds());
        return adminUser.getId();
    }

    @Override
    public void update(Long id, AdminUserUpdateRequest request, Long currentAdminId) {
        AdminUser adminUser = requireAdminUser(id);
        checkNotDisableSelf(id, request.getStatus(), currentAdminId);

        adminUser.setRealName(request.getRealName());
        adminUser.setPhone(request.getPhone());
        if (request.getStatus() != null) {
            adminUser.setStatus(request.getStatus());
        }
        adminUserMapper.updateById(adminUser);
    }

    @Override
    public void updateStatus(Long id, Integer status, Long currentAdminId) {
        requireAdminUser(id);
        validateStatus(status);
        checkNotDisableSelf(id, status, currentAdminId);

        AdminUser adminUser = new AdminUser();
        adminUser.setId(id);
        adminUser.setStatus(status);
        adminUserMapper.updateById(adminUser);
    }

    @Override
    public void resetPassword(Long id, String newPassword) {
        requireAdminUser(id);

        AdminUser adminUser = new AdminUser();
        adminUser.setId(id);
        adminUser.setPassword(passwordEncoder.encode(newPassword));
        adminUserMapper.updateById(adminUser);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long id, List<Long> roleIds) {
        requireAdminUser(id);
        validateRolesExist(roleIds);
        replaceRoles(id, roleIds);
    }

    /**
     * 批量组装 VO：一次查出本页所有管理员的角色关系，避免逐条查询（N+1）
     */
    private List<AdminUserVO> toVOList(List<AdminUser> users) {
        if (users == null || users.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> userIds = users.stream().map(AdminUser::getId).collect(Collectors.toList());
        List<AdminUserRole> relations = adminUserRoleMapper.selectList(
                Wrappers.<AdminUserRole>lambdaQuery().in(AdminUserRole::getAdminUserId, userIds));

        Set<Long> roleIds = relations.stream().map(AdminUserRole::getRoleId).collect(Collectors.toSet());
        Map<Long, String> roleNameMap = roleIds.isEmpty() ? Map.of()
                : roleMapper.selectList(Wrappers.<Role>lambdaQuery().in(Role::getId, roleIds))
                .stream().collect(Collectors.toMap(Role::getId, Role::getName, (a, b) -> a));

        Map<Long, List<Long>> userRoleIds = new HashMap<>();
        Map<Long, List<String>> userRoleNames = new HashMap<>();
        for (AdminUserRole relation : relations) {
            userRoleIds.computeIfAbsent(relation.getAdminUserId(), k -> new ArrayList<>())
                    .add(relation.getRoleId());
            String roleName = roleNameMap.get(relation.getRoleId());
            if (roleName != null) {
                userRoleNames.computeIfAbsent(relation.getAdminUserId(), k -> new ArrayList<>())
                        .add(roleName);
            }
        }

        List<AdminUserVO> result = new ArrayList<>(users.size());
        for (AdminUser user : users) {
            result.add(toVO(user,
                    userRoleIds.getOrDefault(user.getId(), List.of()),
                    userRoleNames.getOrDefault(user.getId(), List.of())));
        }
        return result;
    }

    private AdminUserVO toVO(AdminUser adminUser, List<Long> roleIds, List<String> roleNames) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(adminUser.getId());
        vo.setUsername(adminUser.getUsername());
        vo.setRealName(adminUser.getRealName());
        vo.setPhone(adminUser.getPhone());
        vo.setStatus(adminUser.getStatus());
        vo.setLastLoginTime(adminUser.getLastLoginTime());
        vo.setCreateTime(adminUser.getCreateTime());
        vo.setRoleIds(roleIds);
        vo.setRoleNames(roleNames);
        return vo;
    }

    /**
     * 全量覆盖管理员的角色关系：先删后插，保证幂等
     */
    private void replaceRoles(Long adminUserId, List<Long> roleIds) {
        adminUserRoleMapper.delete(Wrappers.<AdminUserRole>lambdaQuery()
                .eq(AdminUserRole::getAdminUserId, adminUserId));
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        for (Long roleId : roleIds.stream().distinct().collect(Collectors.toList())) {
            AdminUserRole relation = new AdminUserRole();
            relation.setAdminUserId(adminUserId);
            relation.setRoleId(roleId);
            adminUserRoleMapper.insert(relation);
        }
    }

    private AdminUser requireAdminUser(Long id) {
        AdminUser adminUser = adminUserMapper.selectById(id);
        if (adminUser == null) {
            throw new BusinessException(ResultCode.ADMIN_USER_NOT_FOUND);
        }
        return adminUser;
    }

    /**
     * 用户名唯一性校验
     *
     * <p>注意这里按「未删除」范围校验：{@code admin_user} 为逻辑删除，
     * 因此被软删的历史用户名不会释放，这是刻意保留的行为（避免同名账号反复出现导致审计混乱）。
     */
    private void checkUsernameUnique(String username) {
        Long count = adminUserMapper.selectCount(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, username));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.ADMIN_USERNAME_EXISTS);
        }
    }

    /**
     * 角色必须真实存在，否则会产生悬空的角色绑定
     */
    private void validateRolesExist(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<Long> distinctRoleIds = roleIds.stream().distinct().collect(Collectors.toList());
        Long count = roleMapper.selectCount(
                Wrappers.<Role>lambdaQuery().in(Role::getId, distinctRoleIds));
        if (count == null || count != distinctRoleIds.size()) {
            throw new BusinessException(ResultCode.ADMIN_ROLE_NOT_FOUND);
        }
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != STATUS_ENABLED && status != STATUS_DISABLED)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "状态只能是 1（启用）或 0（禁用）");
        }
    }

    /**
     * 禁止把自己禁用：当前会话会立刻失去后台访问能力，属于自锁操作
     */
    private void checkNotDisableSelf(Long id, Integer status, Long currentAdminId) {
        if (status != null && status != STATUS_ENABLED && Objects.equals(id, currentAdminId)) {
            throw new BusinessException(ResultCode.ADMIN_SELF_OPERATION_FORBIDDEN);
        }
    }
}
