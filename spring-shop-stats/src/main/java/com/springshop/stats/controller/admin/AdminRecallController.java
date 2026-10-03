package com.springshop.stats.controller.admin;

import com.springshop.common.result.Result;
import com.springshop.stats.entity.CartRecallProduct;
import com.springshop.stats.entity.CartRecallTarget;
import com.springshop.stats.service.CartRecallService;
import com.springshop.stats.task.CartRecallTask;
import com.springshop.stats.vo.RecallBuildResultVO;
import com.springshop.stats.vo.RecallSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 加购未买召回圈人接口（后台）
 *
 * <p>走管理后台过滤链（{@code /api/admin/**}），无需改 {@code SecurityConfig} 白名单；
 * 接口权限由 {@code @PreAuthorize} 的权限码控制，需在「菜单管理」中配置对应权限。
 *
 * <p>本轮只做圈人，不发券、不触达。拿到规模与触达覆盖率后再决定是否投入券体系与消息通道。
 */
@Tag(name = "数据运营（后台）", description = "加购未买召回圈人相关接口")
@RestController
@RequestMapping("/api/admin/stats/recall")
public class AdminRecallController {

    private final CartRecallService cartRecallService;
    private final CartRecallTask cartRecallTask;

    public AdminRecallController(CartRecallService cartRecallService, CartRecallTask cartRecallTask) {
        this.cartRecallService = cartRecallService;
        this.cartRecallTask = cartRecallTask;
    }

    @Operation(summary = "待召回池概览（规模、触达覆盖率、状态分布）")
    @PreAuthorize("hasAuthority('stats:recall:list')")
    @GetMapping("/summary")
    public Result<RecallSummaryVO> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statDate) {
        return Result.success(cartRecallService.summary(resolveDate(statDate)));
    }

    @Operation(summary = "选品结果（按弃购率排序的 TOP N 商品）")
    @PreAuthorize("hasAuthority('stats:recall:list')")
    @GetMapping("/products")
    public Result<List<CartRecallProduct>> products(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statDate,
            @RequestParam(required = false) Integer limit) {
        return Result.success(cartRecallService.listProducts(resolveDate(statDate), limit));
    }

    @Operation(summary = "待召回人群明细")
    @PreAuthorize("hasAuthority('stats:recall:list')")
    @GetMapping("/targets")
    public Result<List<CartRecallTarget>> targets(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statDate,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean reachableOnly) {
        return Result.success(cartRecallService.listTargets(resolveDate(statDate), limit, reachableOnly));
    }

    @Operation(summary = "手动执行圈人（补数/重跑，同一天重复执行结果一致）")
    @PreAuthorize("hasAuthority('stats:recall:build')")
    @PostMapping("/build")
    public Result<RecallBuildResultVO> build(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statDate) {
        return Result.success(cartRecallTask.run(resolveDate(statDate)));
    }

    private LocalDate resolveDate(LocalDate statDate) {
        return statDate == null ? cartRecallService.currentStatDate() : statDate;
    }
}
