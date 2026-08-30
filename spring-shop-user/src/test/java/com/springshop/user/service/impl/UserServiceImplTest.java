package com.springshop.user.service.impl;

import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.JwtTokenProvider;
import com.springshop.user.dto.LoginRequest;
import com.springshop.user.dto.RegisterRequest;
import com.springshop.user.entity.User;
import com.springshop.user.mapper.UserMapper;
import com.springshop.user.vo.LoginResponse;
import com.springshop.user.vo.UserInfoVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserServiceImpl 单元测试（Mockito 隔离数据库与外部依赖）
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private UserServiceImpl userService;

    // ---------- 注册 ----------

    @Test
    void register_shouldThrowWhenUsernameExists() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("123456");

        BusinessException e = assertThrows(BusinessException.class, () -> userService.register(request));
        assertEquals(ResultCode.USERNAME_EXISTS.getCode(), e.getCode());
        // 用户名已存在时不应执行插入
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_shouldEncodePasswordAndInsert() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("123456")).thenReturn("$2a$10$encoded");

        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("123456");
        request.setPhone("13800138000");

        userService.register(request);

        verify(passwordEncoder).encode("123456");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User user = captor.getValue();
        assertEquals("alice", user.getUsername());
        assertEquals("$2a$10$encoded", user.getPassword());
        // 昵称缺省回退为用户名
        assertEquals("alice", user.getNickname());
        assertEquals("13800138000", user.getPhone());
        assertEquals(1, user.getStatus());
    }

    @Test
    void register_shouldKeepCustomNickname() {
        when(userMapper.selectCount(any())).thenReturn(0L);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setPassword("123456");
        request.setNickname("爱丽丝");

        userService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("爱丽丝", captor.getValue().getNickname());
    }

    // ---------- 登录 ----------

    @Test
    void login_shouldThrowWhenUserNotFound() {
        when(userMapper.selectOne(any())).thenReturn(null);

        LoginRequest request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("123456");

        BusinessException e = assertThrows(BusinessException.class, () -> userService.login(request));
        // 用户不存在与密码错误统一提示，防用户名枚举
        assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void login_shouldThrowWhenPasswordMismatch() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("hash");
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("wrong");

        BusinessException e = assertThrows(BusinessException.class, () -> userService.login(request));
        assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode());
        verify(jwtTokenProvider, never()).generateToken(any(), any());
    }

    @Test
    void login_shouldThrowWhenUserDisabled() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("hash");
        user.setStatus(0); // 禁用
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("123456");

        BusinessException e = assertThrows(BusinessException.class, () -> userService.login(request));
        assertEquals(ResultCode.USER_DISABLED.getCode(), e.getCode());
    }

    @Test
    void login_shouldReturnTokenAndUserInfo() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("hash");
        user.setNickname("爱丽丝");
        user.setPhone("13800138000");
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);
        when(jwtTokenProvider.generateToken(1L, "alice")).thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("123456");

        LoginResponse response = userService.login(request);

        assertEquals("jwt-token", response.getToken());
        UserInfoVO vo = response.getUser();
        assertEquals(1L, vo.getId());
        assertEquals("alice", vo.getUsername());
        assertEquals("爱丽丝", vo.getNickname());
        // VO 不应携带密码
        verify(jwtTokenProvider).generateToken(eq(1L), eq("alice"));
    }

    // ---------- 当前用户 ----------

    @Test
    void getCurrentUser_shouldThrowWhenNotFound() {
        when(userMapper.selectById(99L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> userService.getCurrentUser(99L));
        assertEquals(ResultCode.USER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void getCurrentUser_shouldReturnUserInfo() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setNickname("爱丽丝");
        user.setPhone("13800138000");
        when(userMapper.selectById(1L)).thenReturn(user);

        UserInfoVO vo = userService.getCurrentUser(1L);

        assertEquals(1L, vo.getId());
        assertEquals("alice", vo.getUsername());
        assertEquals("爱丽丝", vo.getNickname());
    }
}
