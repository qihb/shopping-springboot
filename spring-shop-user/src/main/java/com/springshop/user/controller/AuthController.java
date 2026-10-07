package com.springshop.user.controller;

import com.springshop.common.result.Result;
import com.springshop.user.dto.LoginRequest;
import com.springshop.user.dto.MiniAppLoginRequest;
import com.springshop.user.dto.RegisterRequest;
import com.springshop.user.service.UserService;
import com.springshop.user.vo.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册、登录、小程序登录、登出
 */
@Tag(name = "认证", description = "注册与登录（多端）")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "注册", description = "用户名唯一，密码加密存储")
    @ApiResponse(responseCode = "200", description = "注册成功，无返回数据")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return Result.success();
    }

    @Operation(summary = "登录", description = "校验通过后签发 JWT token，clientId 取自 X-Client-Id 请求头")
    @ApiResponse(responseCode = "200", description = "返回登录 token 与用户信息")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request));
    }

    @Operation(summary = "小程序登录", description = "用 wx.login 的 code 换 openid，未注册自动创建用户并签发 token")
    @ApiResponse(responseCode = "200", description = "返回登录 token 与用户信息")
    @PostMapping("/miniapp/login")
    public Result<LoginResponse> miniAppLogin(@Valid @RequestBody MiniAppLoginRequest request) {
        return Result.success(userService.miniAppLogin(request));
    }

    @Operation(summary = "退出登录", description = "token 加入黑名单，主动失效")
    @ApiResponse(responseCode = "200", description = "退出登录成功，无返回数据")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            userService.logout(header.substring(BEARER_PREFIX.length()));
        }
        return Result.success();
    }
}
