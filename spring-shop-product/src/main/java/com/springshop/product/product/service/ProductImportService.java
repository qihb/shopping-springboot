package com.springshop.product.product.service;

import com.springshop.product.product.vo.ProductImportResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 商品批量导入服务
 */
public interface ProductImportService {

    /**
     * 从 Excel 批量导入商品
     *
     * <p>模板为「一行一个 SKU」，同名商品的多行会自动聚合成一个 SPU。
     * 部分成功策略：合法行写入，非法行跳过并在结果里给出原因。
     *
     * @param file 上传的 xls / xlsx 文件
     * @return 导入结果（含逐行错误明细）
     */
    ProductImportResultVO importProducts(MultipartFile file);

    /** 生成导入模板（xlsx 字节内容） */
    byte[] buildTemplate();
}
