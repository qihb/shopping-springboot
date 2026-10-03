package com.springshop.admin.vo;

import com.springshop.common.excel.ImportError;

import java.util.List;

/**
 * 管理员导入结果
 *
 * <p>采用「部分成功」策略：合法行照常写入，非法行跳过并在 {@code errors} 中逐行说明原因，
 * 让运营一次导入就能看清全部问题，而不是改一行试一次。
 */
public class AdminUserImportResultVO {

    /** 文件中的数据行总数（不含表头） */
    private int totalRows;

    /** 成功导入的管理员数量 */
    private int successCount;

    /** 失败行数 */
    private int failCount;

    /** 失败明细 */
    private List<ImportError> errors;

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public List<ImportError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImportError> errors) {
        this.errors = errors;
    }
}
