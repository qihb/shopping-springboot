package com.springshop.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.admin.entity.AdminUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理员表 Mapper
 */
public interface AdminUserMapper extends BaseMapper<AdminUser> {

    /**
     * 批量插入管理员（批量导入用）
     *
     * <p>多值 INSERT 把「上万次单条插入的网络往返」压成「行数 / 批大小」次；
     * {@code useGeneratedKeys} 会把自增主键按顺序回填到每个实体上，
     * 后续 {@code admin_user_role} 的关联行才能拿到 adminUserId。
     *
     * <p>create_time / update_time / is_deleted / version 都有库级默认值，不用显式写。
     */
    @Insert("<script>"
            + "INSERT INTO admin_user (username, password, real_name, phone, status) VALUES "
            + "<foreach collection='list' item='u' separator=','>"
            + "(#{u.username}, #{u.password}, #{u.realName}, #{u.phone}, #{u.status})"
            + "</foreach>"
            + "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBatch(@Param("list") List<AdminUser> adminUsers);
}
