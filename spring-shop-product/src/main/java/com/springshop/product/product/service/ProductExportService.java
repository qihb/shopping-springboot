package com.springshop.product.product.service;

import com.springshop.common.excel.task.ExcelTaskVO;
import com.springshop.product.product.dto.ProductExportQuery;

/**
 * 商品导出服务
 */
public interface ProductExportService {

    /** 业务类型编码：用于任务台账区分与「同类任务去重」 */
    String BIZ_TYPE = "PRODUCT_EXPORT";

    /** 业务类型展示名 */
    String BIZ_NAME = "商品导出";

    /**
     * 受理商品导出
     *
     * <p>与导入一样是「受理即返回」：导出十万行同样要跑很久，同步下载会被网关超时切断。
     * 后台线程按页拉取、边拉边写盘，完成后从任务中心下载。
     *
     * @param query   导出条件（分类 / 关键字 / 状态，或直接指定 id 列表）
     * @param adminId 提交人（管理员 id）
     */
    ExcelTaskVO submitExport(ProductExportQuery query, Long adminId);
}
