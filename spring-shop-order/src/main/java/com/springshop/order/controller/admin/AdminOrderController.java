package com.springshop.order.controller.admin;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.order.dto.AdminOrderPageQuery;
import com.springshop.order.service.OrderService;
import com.springshop.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单管理接口（后台）
 *
 * <p>走管理后台过滤链，接口需管理员身份且通过 {@code @PreAuthorize} 校验菜单权限码。
 */
@Tag(name = "订单管理（后台）", description = "管理后台订单相关接口")
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "订单分页（可按订单号/状态筛选）")
    @PreAuthorize("hasAuthority('order:order:list')")
    @GetMapping
    public Result<PageResult<OrderVO>> page(@Valid AdminOrderPageQuery query) {
        return Result.success(orderService.pageForAdmin(query));
    }

    @Operation(summary = "发货")
    @PreAuthorize("hasAuthority('order:order:ship')")
    @PostMapping("/{orderNo}/ship")
    public Result<Void> ship(@PathVariable String orderNo) {
        orderService.ship(orderNo);
        return Result.success();
    }
}
