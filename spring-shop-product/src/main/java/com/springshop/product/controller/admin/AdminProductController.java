package com.springshop.product.controller.admin;

import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import com.springshop.product.product.dto.ProductExportQuery;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.service.ProductExportService;
import com.springshop.product.product.service.ProductImportService;
import com.springshop.product.product.service.ProductManageService;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
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
    private final ProductExportService productExportService;

    public AdminProductController(ProductManageService productManageService,
                                  ProductQueryService productQueryService,
                                  ProductImportService productImportService,
                                  ProductExportService productExportService) {
        this.productManageService = productManageService;
        this.productQueryService = productQueryService;
        this.productImportService = productImportService;
        this.productExportService = productExportService;
    }

    @Operation(summary = "后台商品分页")
    @ApiResponse(responseCode = "200", description = "分页返回后台商品列表")
    @PreAuthorize("hasAuthority('product:product:list')")
    @GetMapping
    public Result<PageResult<ProductListVO>> page(@Valid @ParameterObject ProductPageQuery query) {
        return Result.success(productQueryService.adminPage(query));
    }

    @Operation(summary = "后台商品详情")
    @ApiResponse(responseCode = "200", description = "返回商品详情，含 SKU 与图片")
    @PreAuthorize("hasAuthority('product:product:list')")
    @GetMapping("/{id}")
    public Result<ProductDetailVO> detail(@Parameter(description = "商品 id") @PathVariable Long id) {
        return Result.success(productQueryService.adminDetail(id));
    }

    @Operation(summary = "创建商品")
    @ApiResponse(responseCode = "200", description = "创建成功，返回新增商品 id")
    @PreAuthorize("hasAuthority('product:product:create')")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProductSaveRequest request) {
        return Result.success(productManageService.create(request));
    }

    @Operation(summary = "修改商品")
    @ApiResponse(responseCode = "200", description = "修改成功，无返回数据")
    @PreAuthorize("hasAuthority('product:product:update')")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "商品 id") @PathVariable Long id,
                               @Valid @RequestBody ProductSaveRequest request) {
        productManageService.update(id, request);
        return Result.success();
    }

    @Operation(summary = "上下架商品")
    @ApiResponse(responseCode = "200", description = "上下架操作成功，无返回数据")
    @PreAuthorize("hasAuthority('product:product:update')")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@Parameter(description = "商品 id") @PathVariable Long id,
                                     @RequestParam Integer status) {
        productManageService.updateStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "批量导入商品",
            description = "异步受理：一行一个 SKU，同名商品自动聚合为一个 SPU。"
                    + "立即返回任务号，进度与失败明细在任务中心查看")
    @ApiResponse(responseCode = "200", description = "返回异步导入任务信息，可用任务号查询进度")
    @PreAuthorize("hasAuthority('product:product:import')")
    @PostMapping("/import")
    public Result<ExcelTaskVO> importProducts(@RequestPart("file") MultipartFile file) {
        return Result.success(productImportService.submitImport(file, UserContext.getUserId()));
    }

    @Operation(summary = "下载商品导入模板")
    @ApiResponse(responseCode = "200", description = "返回商品导入模板 Excel 文件")
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

    @Operation(summary = "导出商品",
            description = "异步受理：按筛选条件导出，传 ids 则只导出选中的商品。"
                    + "立即返回任务号，完成后从任务中心下载文件")
    @ApiResponse(responseCode = "200", description = "返回异步导出任务信息，可用任务号查询进度")
    @PreAuthorize("hasAuthority('product:product:list')")
    @PostMapping("/export")
    public Result<ExcelTaskVO> export(@RequestBody ProductExportQuery query) {
        return Result.success(productExportService.submitExport(query, UserContext.getUserId()));
    }
}
