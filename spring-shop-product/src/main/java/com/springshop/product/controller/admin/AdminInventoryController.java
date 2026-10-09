package com.springshop.product.controller.admin;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import com.springshop.product.product.dto.InventoryAdjustRequest;
import com.springshop.product.product.dto.InventoryLogQuery;
import com.springshop.product.product.service.InventoryService;
import com.springshop.product.product.vo.InventoryLogVO;
import com.springshop.product.product.vo.InventoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台库存接口
 *
 * <p>走管理后台过滤链（{@code /api/admin/**}），无需改 {@code SecurityConfig}。
 * 库存为独立表（{@code inventory}，sku_id 唯一），变更流水在 {@code inventory_log}。
 */
@Tag(name = "后台库存管理")
@RestController
@RequestMapping("/api/admin/inventory")
public class AdminInventoryController {

    private final InventoryService inventoryService;

    public AdminInventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Operation(summary = "查询 SKU 库存（在库 / 锁定 / 可售）")
    @ApiResponse(responseCode = "200", description = "返回该 SKU 的库存三量")
    @PreAuthorize("hasAuthority('product:inventory:list')")
    @GetMapping("/skus/{skuId}")
    public Result<InventoryVO> detail(@Parameter(description = "SKU id") @PathVariable Long skuId) {
        return Result.success(inventoryService.getBySkuId(skuId));
    }

    @Operation(summary = "分页查询 SKU 库存流水")
    @ApiResponse(responseCode = "200", description = "按 id 倒序返回该 SKU 的库存变更流水")
    @PreAuthorize("hasAuthority('product:inventory:list')")
    @GetMapping("/skus/{skuId}/logs")
    public Result<PageResult<InventoryLogVO>> logs(@Parameter(description = "SKU id") @PathVariable Long skuId,
                                                   @Valid @ParameterObject InventoryLogQuery query) {
        return Result.success(inventoryService.pageLogs(skuId, query));
    }

    @Operation(summary = "调整 SKU 在库量",
            description = "绝对赋值；不允许调到低于当前锁定量，调整会写入库存流水")
    @ApiResponse(responseCode = "200", description = "调整成功，返回调整后的库存三量")
    @PreAuthorize("hasAuthority('product:inventory:adjust')")
    @PutMapping("/skus/{skuId}/stock")
    public Result<InventoryVO> adjust(@Parameter(description = "SKU id") @PathVariable Long skuId,
                                      @Valid @RequestBody InventoryAdjustRequest request) {
        inventoryService.adjust(skuId, request.getStock(), UserContext.getUserId(), request.getRemark());
        return Result.success(inventoryService.getBySkuId(skuId));
    }
}
