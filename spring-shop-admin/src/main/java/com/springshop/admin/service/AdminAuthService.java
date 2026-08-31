package com.springshop.admin.service;

import com.springshop.admin.dto.AdminLoginRequest;
import com.springshop.admin.vo.AdminLoginResponse;
import com.springshop.admin.vo.AdminUserInfoVO;

/**
 * 管理后台认证服务
 */
public interface AdminAuthService {

    /**
     * 管理员登录：校验密码 + 失败次数锁定，成功后签发 ADMIN token
     */
    AdminLoginResponse login(AdminLoginRequest request);

    /**
     * 退出登录：token 加入 Redis 黑名单实现主动失效
     */
    void logout(String token);

    /**
     * 获取当前登录管理员信息（含角色与权限）
     */
    AdminUserInfoVO getCurrentAdmin(Long adminUserId);
}
