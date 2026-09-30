package com.springshop.order.controller;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import com.springshop.order.dto.OrderCreateRequest;
import com.springshop.order.dto.OrderPageQuery;
import com.springshop.order.service.OrderService;
import com.springshop.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（均需登录，用户 id 取自 {@link UserContext}）
 */
@Tag(name = "订单", description = "前台订单相关接口")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "下单（来源为购物车勾选项），返回订单号")
    @PostMapping
    public Result<String> create(@Valid @RequestBody OrderCreateRequest request) {
        return Result.success(orderService.create(UserContext.getUserId(), request));
    }

    @Operation(summary = "我的订单分页")
    @GetMapping
    public Result<PageResult<OrderVO>> pageMine(@Valid OrderPageQuery query) {
        return Result.success(orderService.pageMine(UserContext.getUserId(), query));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@PathVariable String orderNo) {
        return Result.success(orderService.detail(UserContext.getUserId(), orderNo));
    }

    @Operation(summary = "模拟支付：待付款 → 待发货")
    @PostMapping("/{orderNo}/pay")
    public Result<Void> pay(@PathVariable String orderNo) {
        orderService.pay(UserContext.getUserId(), orderNo);
        return Result.success();
    }

    @Operation(summary = "取消订单：待付款 → 已取消（回滚库存）")
    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancel(@PathVariable String orderNo) {
        orderService.cancel(UserContext.getUserId(), orderNo);
        return Result.success();
    }

    @Operation(summary = "确认收货：待收货 → 已完成")
    @PostMapping("/{orderNo}/confirm")
    public Result<Void> confirm(@PathVariable String orderNo) {
        orderService.confirm(UserContext.getUserId(), orderNo);
        return Result.success();
    }
}
