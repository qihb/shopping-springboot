package com.springshop.product.controller.app;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台商品接口（公开访问）
 */
@Tag(name = "前台商品")
@RestController
@RequestMapping("/api/products")
public class AppProductController {

    private final ProductQueryService productQueryService;

    public AppProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @Operation(summary = "前台商品分页（仅上架）")
    @ApiResponse(responseCode = "200", description = "分页返回商品列表，仅含上架商品")
    @GetMapping
    public Result<PageResult<ProductListVO>> page(@ParameterObject ProductPageQuery query) {
        return Result.success(productQueryService.appPage(query));
    }

    @Operation(summary = "前台商品详情（仅上架）")
    @ApiResponse(responseCode = "200", description = "返回商品详情，含 SKU 与图片")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@Parameter(description = "商品 id") @PathVariable Long id) {
        return Result.success(productQueryService.appDetail(id));
    }
}
