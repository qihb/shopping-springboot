package com.springshop.admin.service;

import com.springshop.admin.dto.AdminUserExportQuery;
import com.springshop.common.excel.task.ExcelTaskVO;

/**
 * 管理员导出服务
 */
public interface AdminUserExportService {

    /** 业务类型编码：用于任务台账区分与「同类任务去重」 */
    String BIZ_TYPE = "ADMIN_USER_EXPORT";

    /** 业务类型展示名 */
    String BIZ_NAME = "管理员导出";

    /**
     * 受理管理员导出
     *
     * @param query   导出条件（用户名 / 姓名 / 状态，或直接指定 id 列表）
     * @param adminId 提交人（管理员 id）
     */
    ExcelTaskVO submitExport(AdminUserExportQuery query, Long adminId);
}
