package com.springshop.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.JwtTokenProvider;
import com.springshop.common.security.RedisKeys;
import com.springshop.user.dto.LoginRequest;
import com.springshop.user.dto.RegisterRequest;
import com.springshop.user.entity.User;
import com.springshop.user.mapper.UserMapper;
import com.springshop.user.service.UserService;
import com.springshop.user.vo.LoginResponse;
import com.springshop.user.vo.UserInfoVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 用户服务实现
 */
@Service
public class UserServiceImpl implements UserService {

    /** 连续登录失败锁定阈值 */
    private static final int MAX_LOGIN_FAIL_COUNT = 5;

    /** 锁定时长（分钟） */
    private static final long LOCK_MINUTES = 15;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final long jwtExpiration;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider,
                           StringRedisTemplate stringRedisTemplate,
                           @Value("${jwt.expiration}") long jwtExpiration) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtExpiration = jwtExpiration;
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
        String failKey = RedisKeys.userLoginFailCount(request.getUsername());
        checkLocked(failKey);

        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, request.getUsername()));

        // 用户不存在与密码错误统一返回同一条提示，避免暴露「用户名是否已注册」
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            increaseFailCount(failKey);
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        // 登录成功：清除失败计数
        stringRedisTemplate.delete(failKey);

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

    /**
     * 检查账号是否处于锁定状态
     */
    private void checkLocked(String failKey) {
        String count = stringRedisTemplate.opsForValue().get(failKey);
        if (count != null && Integer.parseInt(count) >= MAX_LOGIN_FAIL_COUNT) {
            throw new BusinessException(ResultCode.USER_LOCKED);
        }
    }

    /**
     * 登录失败计数 +1，并重置锁定窗口
     */
    private void increaseFailCount(String failKey) {
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(failKey, Duration.ofMinutes(LOCK_MINUTES));
        }
    }
}
