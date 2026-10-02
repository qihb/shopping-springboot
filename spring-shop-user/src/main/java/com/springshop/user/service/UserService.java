package com.springshop.user.service;

import com.springshop.user.dto.LoginRequest;
import com.springshop.user.dto.MiniAppLoginRequest;
import com.springshop.user.dto.RegisterRequest;
import com.springshop.user.vo.LoginResponse;
import com.springshop.user.vo.UserInfoVO;

/**
 * 用户服务
 */
public interface UserService {

    /**
     * 注册：校验用户名唯一、BCrypt 加密密码后落库
     */
    void register(RegisterRequest request);

    /**
     * 登录：校验密码，签发 JWT（clientId 取当前请求的客户端标识）
     */
    LoginResponse login(LoginRequest request);

    /**
     * 小程序登录：用 code 换取 openid，已绑定则登录、未绑定则自动创建用户后签发 JWT
     */
    LoginResponse miniAppLogin(MiniAppLoginRequest request);

    /**
     * 获取当前登录用户信息
     */
    UserInfoVO getCurrentUser(Long userId);

    /**
     * 退出登录：将 token 加入 Redis 黑名单，实现主动失效
     */
    void logout(String token);
}
