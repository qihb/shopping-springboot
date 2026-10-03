package com.springshop.product.controller.admin;

import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.service.ProductImportService;
import com.springshop.product.product.service.ProductManageService;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductImportResultVO;
import com.springshop.product.product.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 后台商品接口
 */
@Tag(name = "后台商品管理")
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ProductManageService productManageService;
    private final ProductQueryService productQueryService;
    private final ProductImportService productImportService;

    public AdminProductController(ProductManageService productManageService,
                                  ProductQueryService productQueryService,
                                  ProductImportService productImportService) {
        this.productManageService = productManageService;
        this.productQueryService = productQueryService;
        this.productImportService = productImportService;
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

    @Operation(summary = "批量导入商品", description = "一行一个 SKU，同名商品自动聚合为一个 SPU；部分成功，非法行在结果中给出原因")
    @PreAuthorize("hasAuthority('product:product:import')")
    @PostMapping("/import")
    public Result<ProductImportResultVO> importProducts(@RequestPart("file") MultipartFile file) {
        return Result.success(productImportService.importProducts(file));
    }

    @Operation(summary = "下载商品导入模板")
    @PreAuthorize("hasAuthority('product:product:import')")
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        // 文件下载无法套 Result<T> 包装，直接返回二进制流（业务失败仍走全局异常处理）
        byte[] content = productImportService.buildTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("商品导入模板.xlsx", StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }
}
