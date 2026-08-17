package com.springshop.user.service;

import com.springshop.user.dto.LoginRequest;
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
     * 登录：校验密码，签发 JWT
     */
    LoginResponse login(LoginRequest request);

    /**
     * 获取当前登录用户信息
     */
    UserInfoVO getCurrentUser(Long userId);
}
