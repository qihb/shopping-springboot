package com.springshop.common.excel.task;

/**
 * Excel 任务方向
 */
public enum ExcelTaskType {

    /** 导入：上传文件 → 解析校验 → 落库 */
    IMPORT(1, "导入"),

    /** 导出：按条件查询 → 分批写盘 → 下载 */
    EXPORT(2, "导出");

    private final int code;

    private final String label;

    ExcelTaskType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
