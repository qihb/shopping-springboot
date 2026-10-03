package com.springshop.admin.service.impl;

import com.springshop.admin.dto.AdminUserExportQuery;
import com.springshop.admin.service.AdminUserExportService;
import com.springshop.admin.service.AdminUserService;
import com.springshop.admin.vo.AdminUserExportRow;
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
 * 管理员导出服务实现
 *
 * <p>复用列表页的查询与 VO 组装（含角色名），保证「页面上看到的」与「导出的」一致。
 */
@Service
public class AdminUserExportServiceImpl implements AdminUserExportService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserExportServiceImpl.class);

    private static final String SHEET_NAME = "管理员列表";

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AdminUserService adminUserService;

    private final ExcelTaskExecutor excelTaskExecutor;

    public AdminUserExportServiceImpl(AdminUserService adminUserService,
                                      ExcelTaskExecutor excelTaskExecutor) {
        this.adminUserService = adminUserService;
        this.excelTaskExecutor = excelTaskExecutor;
    }

    @Override
    public ExcelTaskVO submitExport(AdminUserExportQuery query, Long adminId) {
        AdminUserExportQuery safeQuery = query == null ? new AdminUserExportQuery() : query;
        String fileName = "管理员列表-" + LocalDateTime.now().format(FILE_TIME) + ".xlsx";
        return excelTaskExecutor.submitExport(BIZ_TYPE, BIZ_NAME, adminId, fileName, safeQuery,
                this::processExport);
    }

    private void processExport(ExcelTaskContext context) throws IOException {
        int exported = ExcelExportSupport.export(context, AdminUserExportQuery.class,
                AdminUserExportRow.class, SHEET_NAME,
                (query, current, pageSize) -> adminUserService.exportPage(query, current, pageSize)
                        .stream().map(AdminUserExportRow::from).toList());
        log.info("管理员导出完成 taskNo={} 共 {} 行", context.getTaskNo(), exported);
    }
}
