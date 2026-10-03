package com.springshop.admin.controller;

import com.springshop.admin.dto.OperationLogPageQuery;
import com.springshop.admin.service.OperationLogService;
import com.springshop.admin.vo.OperationLogVO;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Operation(summary = "分页查询操作日志")
    @PreAuthorize("hasAuthority('system:log:list')")
    @GetMapping
    public Result<PageResult<OperationLogVO>> page(@Valid OperationLogPageQuery query) {
        return Result.success(operationLogService.page(query));
    }
}
