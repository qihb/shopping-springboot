package com.springshop.order.controller.admin;

import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import com.springshop.order.dto.AdminOrderPageQuery;
import com.springshop.order.dto.OrderExportQuery;
import com.springshop.order.service.OrderExportService;
import com.springshop.order.service.OrderService;
import com.springshop.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final OrderExportService orderExportService;

    public AdminOrderController(OrderService orderService,
                                OrderExportService orderExportService) {
        this.orderService = orderService;
        this.orderExportService = orderExportService;
    }

    @Operation(summary = "订单分页（可按订单号/状态筛选）")
    @PreAuthorize("hasAuthority('order:order:list')")
    @GetMapping
    public Result<PageResult<OrderVO>> page(@Valid AdminOrderPageQuery query) {
        return Result.success(orderService.pageForAdmin(query));
    }

    @Operation(summary = "导出订单",
            description = "异步受理：按筛选条件导出全部命中订单，传 ids 则只导出选中的订单。"
                    + "立即返回任务号，完成后从任务中心下载文件")
    @PreAuthorize("hasAuthority('order:order:list')")
    @PostMapping("/export")
    public Result<ExcelTaskVO> export(@RequestBody OrderExportQuery query) {
        return Result.success(orderExportService.submitExport(query, UserContext.getUserId()));
    }

    @Operation(summary = "发货")
    @PreAuthorize("hasAuthority('order:order:ship')")
    @PostMapping("/{orderNo}/ship")
    public Result<Void> ship(@PathVariable String orderNo) {
        orderService.ship(orderNo);
        return Result.success();
    }
}
