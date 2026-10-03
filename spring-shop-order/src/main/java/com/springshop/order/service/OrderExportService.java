package com.springshop.order.service;

import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.order.dto.OrderExportQuery;

/**
 * 订单导出服务
 */
public interface OrderExportService {

    /** 业务类型编码：用于任务台账区分与「同类任务去重」 */
    String BIZ_TYPE = "ORDER_EXPORT";

    /** 业务类型展示名 */
    String BIZ_NAME = "订单导出";

    /**
     * 受理订单导出
     *
     * @param query   导出条件（订单号 / 状态 / 选中 id）
     * @param adminId 提交人（管理员 id）
     */
    ExcelTaskVO submitExport(OrderExportQuery query, Long adminId);
}
