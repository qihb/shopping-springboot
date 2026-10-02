package com.springshop.user.service.impl;

import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.ClientType;
import com.springshop.common.security.JwtTokenProvider;
import com.springshop.common.security.RedisKeys;
import com.springshop.user.client.MiniAppAuthClient;
import com.springshop.user.dto.LoginRequest;
import com.springshop.user.dto.MiniAppLoginRequest;
import com.springshop.user.dto.RegisterRequest;
import com.springshop.user.entity.User;
import com.springshop.user.mapper.UserMapper;
import com.springshop.user.vo.LoginResponse;
import com.springshop.user.vo.UserInfoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    @Mock
    private MiniAppAuthClient miniAppAuthClient;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        // @Value primitive long 无法被 Mockito 注入，手动构造并显式传 token 有效期
        userService = new UserServiceImpl(userMapper, passwordEncoder, jwtTokenProvider,
                miniAppAuthClient, stringRedisTemplate, 7200000L);
        // register/getCurrentUser 等用例不触碰 Redis，用 lenient 规避严格模式的 UnnecessaryStubbing
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

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
        verify(jwtTokenProvider, never()).generateToken(any(), any(), any(), any());
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
        when(jwtTokenProvider.generateToken(1L, "alice", JwtTokenProvider.USER_TYPE_USER, ClientType.WEB.getCode()))
                .thenReturn("jwt-token");

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
        verify(jwtTokenProvider).generateToken(eq(1L), eq("alice"),
                eq(JwtTokenProvider.USER_TYPE_USER), eq(ClientType.WEB.getCode()));
    }

    @Test
    void login_shouldThrowWhenLocked() {
        // 模拟 Redis 中失败计数已达阈值（5 次）
        when(valueOperations.get(RedisKeys.userLoginFailCount("alice"))).thenReturn("5");

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("123456");

        BusinessException e = assertThrows(BusinessException.class, () -> userService.login(request));
        assertEquals(ResultCode.USER_LOCKED.getCode(), e.getCode());
        // 锁定期间不应查库验证密码
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void login_shouldIncreaseFailCountWhenPasswordWrong() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("hash");
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(stringRedisTemplate.opsForValue().increment(RedisKeys.userLoginFailCount("alice"))).thenReturn(1L);

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("wrong");

        BusinessException e = assertThrows(BusinessException.class, () -> userService.login(request));
        assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode());
        // 首次失败：计数 +1 并设置锁定窗口
        verify(stringRedisTemplate.opsForValue()).increment(RedisKeys.userLoginFailCount("alice"));
        verify(stringRedisTemplate).expire(eq(RedisKeys.userLoginFailCount("alice")), any(Duration.class));
        // 未登录成功，不应清除失败计数
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void login_shouldClearFailCountOnSuccess() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("hash");
        user.setStatus(1);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(jwtTokenProvider.generateToken(1L, "alice", JwtTokenProvider.USER_TYPE_USER, ClientType.WEB.getCode()))
                .thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("123456");

        LoginResponse response = userService.login(request);

        assertEquals("jwt-token", response.getToken());
        // 登录成功必须清除失败计数
        verify(stringRedisTemplate).delete(RedisKeys.userLoginFailCount("alice"));
    }

    // ---------- 登出 ----------

    @Test
    void logout_shouldAddTokenToBlacklist() {
        userService.logout("token-abc");

        // 黑名单 TTL 与 token 有效期一致（测试构造器显式传 7200000L）
        verify(valueOperations).set(
                eq(RedisKeys.userTokenBlacklist("token-abc")),
                eq("1"),
                eq(Duration.ofMillis(7200000L)));
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

    // ---------- 小程序登录 ----------

    @Test
    void miniAppLogin_shouldCreateUserWhenOpenidNotBound() {
        when(miniAppAuthClient.getOpenid("code-1")).thenReturn("openid-1");
        when(userMapper.selectOne(any())).thenReturn(null);
        // 模拟插入后回填自增主键
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User inserted = invocation.getArgument(0);
            inserted.setId(10L);
            return 1;
        });
        when(jwtTokenProvider.generateToken(any(), any(), any(), any())).thenReturn("mini-jwt");

        MiniAppLoginRequest request = new MiniAppLoginRequest();
        request.setCode("code-1");

        LoginResponse response = userService.miniAppLogin(request);

        assertEquals("mini-jwt", response.getToken());
        assertEquals(10L, response.getUser().getId());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User created = captor.getValue();
        assertEquals("openid-1", created.getOpenid());
        assertEquals("微信用户", created.getNickname());
        assertTrue(created.getUsername().startsWith("wx_"));
        assertEquals(1, created.getStatus());
    }

    @Test
    void miniAppLogin_shouldReuseExistingUserAndSkipInsert() {
        User existing = new User();
        existing.setId(1L);
        existing.setUsername("wx_existing");
        existing.setNickname("老用户");
        existing.setOpenid("openid-1");
        existing.setStatus(1);
        when(miniAppAuthClient.getOpenid("code-1")).thenReturn("openid-1");
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(jwtTokenProvider.generateToken(any(), any(), any(), any())).thenReturn("mini-jwt");

        MiniAppLoginRequest request = new MiniAppLoginRequest();
        request.setCode("code-1");

        LoginResponse response = userService.miniAppLogin(request);

        assertEquals("mini-jwt", response.getToken());
        assertEquals(1L, response.getUser().getId());
        // 已绑定 openid 不应重复创建用户
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void miniAppLogin_shouldEmbedMiniAppClientIdInToken() {
        User existing = new User();
        existing.setId(1L);
        existing.setUsername("wx_existing");
        existing.setOpenid("openid-1");
        existing.setStatus(1);
        when(miniAppAuthClient.getOpenid("code-1")).thenReturn("openid-1");
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(jwtTokenProvider.generateToken(any(), any(), any(), any())).thenReturn("mini-jwt");

        MiniAppLoginRequest request = new MiniAppLoginRequest();
        request.setCode("code-1");

        userService.miniAppLogin(request);

        // 小程序登录签发的 token 必须带 MINIAPP 客户端标识
        verify(jwtTokenProvider).generateToken(eq(1L), eq("wx_existing"),
                eq(JwtTokenProvider.USER_TYPE_USER), eq(ClientType.MINIAPP.getCode()));
    }

    @Test
    void miniAppLogin_shouldThrowWhenUserDisabled() {
        User existing = new User();
        existing.setId(1L);
        existing.setUsername("wx_existing");
        existing.setOpenid("openid-1");
        existing.setStatus(0);
        when(miniAppAuthClient.getOpenid("code-1")).thenReturn("openid-1");
        when(userMapper.selectOne(any())).thenReturn(existing);

        MiniAppLoginRequest request = new MiniAppLoginRequest();
        request.setCode("code-1");

        BusinessException e = assertThrows(BusinessException.class, () -> userService.miniAppLogin(request));
        assertEquals(ResultCode.USER_DISABLED.getCode(), e.getCode());
        verify(jwtTokenProvider, never()).generateToken(any(), any(), any(), any());
    }
}
