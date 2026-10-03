package com.springshop.admin.controller;

import com.springshop.admin.aspect.OperationLog;
import com.springshop.admin.dto.AdminChangePasswordRequest;
import com.springshop.admin.service.AdminAuthService;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员个人中心接口
 *
 * <p>刻意不放在 {@code /api/admin/auth/**} 下：该前缀在 SecurityConfig 中是白名单（匿名可访问），
 * 放进去会让改密接口变成无需登录即可调用。
 *
 * <p>这里也不加 {@code @PreAuthorize}：改自己的密码是每个登录管理员的固有能力，
 * 若挂上权限码，刚创建、还没分配角色的管理员将无法自助改密。
 */
@Tag(name = "后台个人中心", description = "修改当前登录管理员的密码")
@RestController
@RequestMapping("/api/admin/profile")
public class AdminProfileController {

    private final AdminAuthService adminAuthService;

    public AdminProfileController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Operation(summary = "修改当前管理员密码", description = "需提供原密码")
    @OperationLog(module = "系统管理", operation = "修改本人密码")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody AdminChangePasswordRequest request) {
        adminAuthService.changePassword(UserContext.getUserId(), request.getOldPassword(), request.getNewPassword());
        return Result.success();
    }
}
