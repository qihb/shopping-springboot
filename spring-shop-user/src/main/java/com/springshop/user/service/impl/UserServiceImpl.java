package com.springshop.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.JwtTokenProvider;
import com.springshop.user.dto.LoginRequest;
import com.springshop.user.dto.RegisterRequest;
import com.springshop.user.entity.User;
import com.springshop.user.mapper.UserMapper;
import com.springshop.user.service.UserService;
import com.springshop.user.vo.LoginResponse;
import com.springshop.user.vo.UserInfoVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户服务实现
 */
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public void register(RegisterRequest request) {
        // 用户名唯一性校验（先查后插，防止重复注册）
        Long count = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }

        User user = new User();
        user.setUsername(request.getUsername());
        // 密码绝不落库明文，BCrypt 自动加盐
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        // 昵称缺省时用用户名
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getUsername());
        user.setPhone(request.getPhone());
        user.setStatus(1);
        userMapper.insert(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, request.getUsername()));

        // 用户不存在与密码错误统一返回同一条提示，避免暴露「用户名是否已注册」
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setUser(convertToVO(user));
        return response;
    }

    @Override
    public UserInfoVO getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return convertToVO(user);
    }

    /**
     * 实体转 VO：对外只暴露必要字段，不返回密码等敏感信息
     */
    private UserInfoVO convertToVO(User user) {
        return new UserInfoVO(user.getId(), user.getUsername(), user.getNickname(), user.getPhone());
    }
}
