package com.springshop.web.controller;

import com.springshop.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口（示例，验证框架可运行）
 */
@Tag(name = "系统管理", description = "健康检查等系统级接口")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "健康检查", description = "验证服务是否正常启动")
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("spring-shop service is running");
    }
}
