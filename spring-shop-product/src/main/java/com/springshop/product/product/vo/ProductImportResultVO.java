package com.springshop.product.product.vo;

import com.springshop.common.excel.ImportError;

import java.util.List;

/**
 * 商品导入结果
 *
 * <p>采用「部分成功」策略：合法行照常创建，非法行跳过并在 {@code errors} 中逐行说明原因。
 * 由于模板是一行一个 SKU、按商品名称聚合成 SPU，
 * 因此「商品数」与「SKU 数」分开统计，便于运营核对导入规模。
 */
public class ProductImportResultVO {

    /** 文件中的数据行总数（不含表头），一行对应一个 SKU */
    private int totalRows;

    /** 成功创建的商品（SPU）数量 */
    private int productCount;

    /** 成功创建的 SKU 数量 */
    private int skuCount;

    /** 失败行数 */
    private int failRowCount;

    /** 失败明细 */
    private List<ImportError> errors;

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getProductCount() {
        return productCount;
    }

    public void setProductCount(int productCount) {
        this.productCount = productCount;
    }

    public int getSkuCount() {
        return skuCount;
    }

    public void setSkuCount(int skuCount) {
        this.skuCount = skuCount;
    }

    public int getFailRowCount() {
        return failRowCount;
    }

    public void setFailRowCount(int failRowCount) {
        this.failRowCount = failRowCount;
    }

    public List<ImportError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImportError> errors) {
        this.errors = errors;
    }
}
