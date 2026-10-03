package com.springshop.admin.service;

import com.springshop.admin.dto.OperationLogExportQuery;
import com.springshop.common.excel.task.ExcelTaskVO;

/**
 * 操作日志导出服务
 */
public interface OperationLogExportService {

    /** 业务类型编码：用于任务台账区分与「同类任务去重」 */
    String BIZ_TYPE = "OPERATION_LOG_EXPORT";

    /** 业务类型展示名 */
    String BIZ_NAME = "操作日志导出";

    /**
     * 受理操作日志导出
     *
     * @param query   导出条件（模块 / 操作人 / 操作 / 结果 / 时间区间）
     * @param adminId 提交人（管理员 id）
     */
    ExcelTaskVO submitExport(OperationLogExportQuery query, Long adminId);
}
