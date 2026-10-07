package com.springshop.admin.controller;

import com.springshop.admin.dto.OperationLogExportQuery;
import com.springshop.admin.dto.OperationLogPageQuery;
import com.springshop.admin.service.OperationLogExportService;
import com.springshop.admin.service.OperationLogService;
import com.springshop.admin.vo.OperationLogVO;
import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import com.springshop.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询接口
 *
 * <p>只读接口，不加 {@code @OperationLog}：查询日志本身不需要再产生一条日志。
 */
@Tag(name = "后台操作日志", description = "查询管理员的敏感操作审计记录")
@RestController
@RequestMapping("/api/admin/operation-logs")
@Validated
public class OperationLogController {

    private final OperationLogService operationLogService;
    private final OperationLogExportService operationLogExportService;

    public OperationLogController(OperationLogService operationLogService,
                                  OperationLogExportService operationLogExportService) {
        this.operationLogService = operationLogService;
        this.operationLogExportService = operationLogExportService;
    }

    @Operation(summary = "分页查询操作日志")
    @ApiResponse(responseCode = "200", description = "分页返回操作日志列表")
    @PreAuthorize("hasAuthority('system:log:list')")
    @GetMapping
    public Result<PageResult<OperationLogVO>> page(@ParameterObject @Valid OperationLogPageQuery query) {
        return Result.success(operationLogService.page(query));
    }

    @Operation(summary = "导出操作日志",
            description = "异步受理：按筛选条件导出全部命中记录（不受列表分页限制）。"
                    + "立即返回任务号，完成后从任务中心下载文件")
    @ApiResponse(responseCode = "200", description = "返回异步导出任务信息，可用任务号查询进度")
    @PreAuthorize("hasAuthority('system:log:list')")
    @PostMapping("/export")
    public Result<ExcelTaskVO> export(@Valid @RequestBody OperationLogExportQuery query) {
        return Result.success(operationLogExportService.submitExport(query, UserContext.getUserId()));
    }
}
