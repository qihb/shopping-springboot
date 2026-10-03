package com.springshop.common.excel;

/**
 * 导入过程中的单行错误
 *
 * <p>商品导入与管理员导入共用同一结构，前端可以用同一套表格渲染失败明细。
 */
public class ImportError {

    /** 出错行号，与 Excel 界面显示的行号一致（表头为第 1 行） */
    private int rowNum;

    /** 失败原因 */
    private String message;

    public ImportError() {
    }

    public ImportError(int rowNum, String message) {
        this.rowNum = rowNum;
        this.message = message;
    }

    public int getRowNum() {
        return rowNum;
    }

    public void setRowNum(int rowNum) {
        this.rowNum = rowNum;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
