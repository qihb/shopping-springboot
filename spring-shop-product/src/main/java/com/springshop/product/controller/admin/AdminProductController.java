package com.springshop.product.controller.admin;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.service.ProductManageService;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台商品接口
 */
@Tag(name = "后台商品管理")
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductManageService productManageService;
    private final ProductQueryService productQueryService;

    public AdminProductController(ProductManageService productManageService,
                                  ProductQueryService productQueryService) {
        this.productManageService = productManageService;
        this.productQueryService = productQueryService;
    }

    @Operation(summary = "后台商品分页")
    @PreAuthorize("hasAuthority('product:product:list')")
    @GetMapping
    public Result<PageResult<ProductListVO>> page(@Valid ProductPageQuery query) {
        return Result.success(productQueryService.adminPage(query));
    }

    @Operation(summary = "后台商品详情")
    @PreAuthorize("hasAuthority('product:product:list')")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(productQueryService.adminDetail(id));
    }

    @Operation(summary = "创建商品")
    @PreAuthorize("hasAuthority('product:product:create')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProductSaveRequest request) {
        return Result.success(productManageService.create(request));
    }

    @Operation(summary = "修改商品")
    @PreAuthorize("hasAuthority('product:product:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest request) {
        productManageService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "上下架商品")
    @PreAuthorize("hasAuthority('product:product:update')")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productManageService.updateStatus(id, status);
        return Result.success();
    }
}
