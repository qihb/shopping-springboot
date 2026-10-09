package com.springshop.product.controller.admin;

import com.springshop.common.result.Result;
import com.springshop.product.product.dto.BrandSaveRequest;
import com.springshop.product.product.service.BrandService;
import com.springshop.product.product.vo.BrandVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台品牌管理接口
 */
@Tag(name = "后台品牌管理")
@RestController
@RequestMapping("/api/admin/brands")
public class AdminBrandController {

    private final BrandService brandService;

    public AdminBrandController(BrandService brandService) {
        this.brandService = brandService;
    }

    @Operation(summary = "品牌列表")
    @ApiResponse(responseCode = "200", description = "返回品牌列表")
    @PreAuthorize("hasAuthority('product:brand:list')")
    @GetMapping
    public Result<List<BrandVO>> list() {
        return Result.success(brandService.list());
    }

    @Operation(summary = "品牌详情")
    @ApiResponse(responseCode = "200", description = "返回品牌详情")
    @PreAuthorize("hasAuthority('product:brand:list')")
    @GetMapping("/{id}")
    public Result<BrandVO> getById(@PathVariable Long id) {
        return Result.success(brandService.getById(id));
    }

    @Operation(summary = "新增品牌")
    @ApiResponse(responseCode = "200", description = "新增品牌成功，无返回数据")
    @PreAuthorize("hasAuthority('product:brand:create')")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody BrandSaveRequest request) {
        brandService.create(request);
        return Result.success();
    }

    @Operation(summary = "修改品牌")
    @ApiResponse(responseCode = "200", description = "修改品牌成功，无返回数据")
    @PreAuthorize("hasAuthority('product:brand:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BrandSaveRequest request) {
        brandService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "删除品牌")
    @ApiResponse(responseCode = "200", description = "删除品牌成功，无返回数据")
    @PreAuthorize("hasAuthority('product:brand:delete')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        brandService.delete(id);
        return Result.success();
    }
}
