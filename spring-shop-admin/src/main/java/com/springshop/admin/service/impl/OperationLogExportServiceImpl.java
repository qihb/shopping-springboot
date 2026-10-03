package com.springshop.admin.service.impl;

import com.springshop.admin.dto.OperationLogExportQuery;
import com.springshop.admin.service.OperationLogExportService;
import com.springshop.admin.service.OperationLogService;
import com.springshop.admin.vo.OperationLogExportRow;
import com.springshop.common.excel.ExcelExportSupport;
import com.springshop.common.excel.task.ExcelTaskContext;
import com.springshop.common.excel.task.ExcelTaskExecutor;
import com.springshop.common.excel.task.ExcelTaskVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 操作日志导出服务实现
 *
 * <p>审计日志的导出量通常是四个导出里最大的（每条敏感操作都留痕），
 * 因此这里尤其依赖分页流式写盘，而不是「一次查全量再写」。
 */
@Service
public class OperationLogExportServiceImpl implements OperationLogExportService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogExportServiceImpl.class);

    private static final String SHEET_NAME = "操作日志";

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OperationLogService operationLogService;

    private final ExcelTaskExecutor excelTaskExecutor;

    public OperationLogExportServiceImpl(OperationLogService operationLogService,
                                         ExcelTaskExecutor excelTaskExecutor) {
        this.operationLogService = operationLogService;
        this.excelTaskExecutor = excelTaskExecutor;
    }

    @Override
    public ExcelTaskVO submitExport(OperationLogExportQuery query, Long adminId) {
        OperationLogExportQuery safeQuery = query == null ? new OperationLogExportQuery() : query;
        String fileName = "操作日志-" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
        return excelTaskExecutor.submitExport(BIZ_TYPE, BIZ_NAME, adminId, fileName, safeQuery,
                this::processExport);
    }

    private void processExport(ExcelTaskContext context) throws IOException {
        int exported = ExcelExportSupport.export(context, OperationLogExportQuery.class,
                OperationLogExportRow.class, SHEET_NAME,
                (query, current, pageSize) -> operationLogService.exportPage(query, current, pageSize)
                        .stream().map(OperationLogExportRow::from).toList());
        log.info("操作日志导出完成 taskNo={} 共 {} 行", context.getTaskNo(), exported);
    }
}
