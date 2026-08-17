package com.springshop.user.controller;

import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import com.springshop.user.service.UserService;
import com.springshop.user.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（需登录访问）
 */
@Tag(name = "用户", description = "当前登录用户相关接口")
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public Result<UserInfoVO> me() {
        // 用户 id 由认证过滤器写入 UserContext，无需前端传参
        return Result.success(userService.getCurrentUser(UserContext.getUserId()));
    }
}
