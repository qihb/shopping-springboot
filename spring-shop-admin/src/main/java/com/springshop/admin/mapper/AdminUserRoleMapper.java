package com.springshop.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.admin.entity.AdminUserRole;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理员-角色关联表 Mapper
 */
public interface AdminUserRoleMapper extends BaseMapper<AdminUserRole> {

    /**
     * 批量插入管理员角色关联（批量导入用）
     *
     * <p>不需要回填主键：关联表的主键没有下游引用。
     */
    @Insert("<script>"
            + "INSERT INTO admin_user_role (admin_user_id, role_id) VALUES "
            + "<foreach collection='list' item='r' separator=','>"
            + "(#{r.adminUserId}, #{r.roleId})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<AdminUserRole> relations);
}
