package com.springshop.admin.service;

import com.springshop.admin.dto.AdminUserCreateRequest;
import com.springshop.admin.dto.AdminUserExportQuery;
import com.springshop.admin.dto.AdminUserPageQuery;
import com.springshop.admin.dto.AdminUserUpdateRequest;
import com.springshop.admin.vo.AdminUserVO;
import com.springshop.common.result.PageResult;

import java.util.List;

/**
 * 管理员账号管理服务
 *
 * <p>刻意不提供「删除管理员」：{@code admin_user} 用的是逻辑删除 + 用户名唯一约束，
 * 软删后用户名仍占位，同名账号无法再次创建（重启时初始化器补建 admin 也会撞唯一键）。
 * 收回权限的正确做法是「禁用」，审计日志也需要账号行长期存在，因此以禁用替代删除。
 */
public interface AdminUserService {

    /** 分页查询管理员（含角色信息） */
    PageResult<AdminUserVO> page(AdminUserPageQuery query);

    /**
     * 导出一页管理员数据
     *
     * <p>与列表页分开是因为导出要突破「单页最多 100 条」的接口层限制，
     * 且不需要总数（按「还有没有下一页」推进即可）。
     *
     * @param current  页码，从 1 开始
     * @param pageSize 每页条数
     */
    List<AdminUserVO> exportPage(AdminUserExportQuery query, long current, long pageSize);

    /** 查询管理员详情 */
    AdminUserVO detail(Long id);

    /** 新增管理员，返回主键 */
    Long create(AdminUserCreateRequest request);

    /** 修改管理员基本信息；currentAdminId 用于拦截「把自己禁用」 */
    void update(Long id, AdminUserUpdateRequest request, Long currentAdminId);

    /** 启用 / 禁用管理员；currentAdminId 用于拦截「把自己禁用」 */
    void updateStatus(Long id, Integer status, Long currentAdminId);

    /** 重置指定管理员的密码（管理员对他人账号的强制操作） */
    void resetPassword(Long id, String newPassword);

    /** 重新分配管理员的角色（全量覆盖） */
    void assignRoles(Long id, List<Long> roleIds);
}
