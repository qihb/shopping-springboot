package com.springshop.admin.controller;

import com.springshop.admin.dto.AdminLoginRequest;
import com.springshop.admin.service.AdminAuthService;
import com.springshop.admin.vo.AdminLoginResponse;
import com.springshop.admin.vo.AdminUserInfoVO;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台认证接口：登录、退出、当前管理员信息
 */
@Tag(name = "后台认证", description = "管理员登录 / 退出 / 当前用户")
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Operation(summary = "管理员登录", description = "连续失败 5 次锁定 15 分钟")
    @PostMapping("/login")
    public Result<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return Result.success(adminAuthService.login(request));
    }

    @Operation(summary = "退出登录", description = "token 加入黑名单，主动失效")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            adminAuthService.logout(header.substring(BEARER_PREFIX.length()));
        }
        return Result.success();
    }

    @Operation(summary = "当前管理员信息", description = "返回角色与权限标识")
    @GetMapping("/me")
    public Result<AdminUserInfoVO> me() {
        return Result.success(adminAuthService.getCurrentAdmin(UserContext.getUserId()));
    }
}
