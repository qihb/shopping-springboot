package com.springshop.pay.controller;

import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import com.springshop.pay.service.PayService;
import com.springshop.pay.vo.PayResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口（需登录，用户 id 取自 {@link UserContext}；无匿名白名单，
 * 由前台过滤链 anyRequest().authenticated() 兜底覆盖 /api/pay/**）
 */
@Tag(name = "支付", description = "前台支付相关接口")
@RestController
@RequestMapping("/api/pay")
public class PayController {

    private final PayService payService;

    public PayController(PayService payService) {
        this.payService = payService;
    }

    @Operation(summary = "模拟支付：订单待付款 → 待发货，返回支付结果")
    @ApiResponse(responseCode = "200", description = "返回支付结果与支付流水信息")
    @PostMapping("/{orderNo}/mockPay")
    public Result<PayResultVO> mockPay(@Parameter(description = "订单号") @NotBlank @PathVariable String orderNo) {
        return Result.success(payService.mockPay(UserContext.getUserId(), orderNo));
    }
}
