package com.springshop.product.product.service;

import com.springshop.common.excel.task.ExcelTaskVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 商品批量导入服务
 */
public interface ProductImportService {

    /** 业务类型编码：用于任务台账区分与「同类任务去重」 */
    String BIZ_TYPE = "PRODUCT_IMPORT";

    /** 业务类型展示名 */
    String BIZ_NAME = "商品导入";

    /**
     * 受理商品批量导入
     *
     * <p>模板为「一行一个 SKU」，同名商品的多行自动聚合成一个 SPU。
     * <p><b>接口语义是「已受理」而不是「已导入」</b>：上万行的文件要跑几十秒到几分钟，
     * 同步等结果会让请求超时。这里只做落盘 + 建任务，立即返回任务号，
     * 前端凭任务号轮询 {@code /api/admin/excel-tasks/{taskNo}} 看进度，
     * 完成后可下载失败明细。
     *
     * @param file    上传的 xls / xlsx 文件
     * @param adminId 提交人（管理员 id）
     * @return 任务信息（含 taskNo）
     */
    ExcelTaskVO submitImport(MultipartFile file, Long adminId);

    /** 生成导入模板（xlsx 字节内容） */
    byte[] buildTemplate();
}
