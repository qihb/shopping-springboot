package com.springshop.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.admin.dto.AdminLoginRequest;
import com.springshop.admin.entity.AdminUser;
import com.springshop.admin.mapper.AdminUserMapper;
import com.springshop.admin.security.AdminUserDetailsService;
import com.springshop.admin.service.AdminAuthService;
import com.springshop.admin.vo.AdminLoginResponse;
import com.springshop.admin.vo.AdminUserInfoVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.JwtTokenProvider;
import com.springshop.common.security.RedisKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * 管理后台认证服务实现
 *
 * <p>生产化设计：
 * <ul>
 *   <li><b>登录失败锁定</b>：连续失败 5 次锁定 15 分钟（计数存 Redis，防止重启丢失）；</li>
 *   <li><b>主动失效</b>：退出登录把 token 加入 Redis 黑名单，黑名单 TTL 与 token 有效期一致；</li>
 *   <li><b>错误提示统一</b>：管理员不存在与密码错误返回同一条提示，避免暴露账号是否存在。</li>
 * </ul>
 */
@Service
public class AdminAuthServiceImpl implements AdminAuthService {

    /** 连续登录失败锁定阈值 */
    private static final int MAX_LOGIN_FAIL_COUNT = 5;

    /** 锁定时长（分钟） */
    private static final long LOCK_MINUTES = 15;

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AdminUserDetailsService adminUserDetailsService;
    private final StringRedisTemplate stringRedisTemplate;
    private final long jwtExpiration;

    public AdminAuthServiceImpl(AdminUserMapper adminUserMapper,
                                PasswordEncoder passwordEncoder,
                                JwtTokenProvider jwtTokenProvider,
                                AdminUserDetailsService adminUserDetailsService,
                                StringRedisTemplate stringRedisTemplate,
                                @Value("${jwt.expiration}") long jwtExpiration) {
        this.adminUserMapper = adminUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.adminUserDetailsService = adminUserDetailsService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtExpiration = jwtExpiration;
    }

    @Override
    public AdminLoginResponse login(AdminLoginRequest request) {
        String failKey = RedisKeys.adminLoginFailCount(request.getUsername());
        checkLocked(failKey);

        AdminUser adminUser = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, request.getUsername()));

        // 管理员不存在与密码错误统一提示
        if (adminUser == null || !passwordEncoder.matches(request.getPassword(), adminUser.getPassword())) {
            increaseFailCount(failKey);
            throw new BusinessException(ResultCode.ADMIN_PASSWORD_ERROR);
        }
        if (adminUser.getStatus() != 1) {
            throw new BusinessException(ResultCode.ADMIN_DISABLED);
        }

        // 登录成功：清除失败计数、更新最近登录时间
        stringRedisTemplate.delete(failKey);
        adminUser.setLastLoginTime(LocalDateTime.now());
        adminUserMapper.updateById(adminUser);

        String token = jwtTokenProvider.generateToken(adminUser.getId(), adminUser.getUsername(),
                JwtTokenProvider.USER_TYPE_ADMIN);
        AdminLoginResponse response = new AdminLoginResponse();
        response.setToken(token);
        response.setAdminUser(toInfoVO(adminUser));
        return response;
    }

    @Override
    public void logout(String token) {
        // 黑名单 TTL 与 token 有效期一致，token 自然过期后自动清理
        stringRedisTemplate.opsForValue().set(
                RedisKeys.adminTokenBlacklist(token), "1", Duration.ofMillis(jwtExpiration));
    }

    @Override
    public AdminUserInfoVO getCurrentAdmin(Long adminUserId) {
        AdminUser adminUser = adminUserMapper.selectById(adminUserId);
        if (adminUser == null) {
            throw new BusinessException(ResultCode.ADMIN_USER_NOT_FOUND);
        }
        return toInfoVO(adminUser);
    }

    /**
     * 组装管理员信息 VO（含角色编码与权限标识）
     */
    private AdminUserInfoVO toInfoVO(AdminUser adminUser) {
        AdminUserInfoVO vo = new AdminUserInfoVO();
        vo.setId(adminUser.getId());
        vo.setUsername(adminUser.getUsername());
        vo.setRealName(adminUser.getRealName());
        vo.setPhone(adminUser.getPhone());
        vo.setRoles(adminUserDetailsService.loadRoles(adminUser.getId()));
        vo.setPermissions(new ArrayList<>(adminUserDetailsService.loadPermissions(adminUser.getId())));
        return vo;
    }

    /**
     * 检查账号是否处于锁定状态
     */
    private void checkLocked(String failKey) {
        String count = stringRedisTemplate.opsForValue().get(failKey);
        if (count != null && Integer.parseInt(count) >= MAX_LOGIN_FAIL_COUNT) {
            throw new BusinessException(ResultCode.ADMIN_LOCKED);
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
