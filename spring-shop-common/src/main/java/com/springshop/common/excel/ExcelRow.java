package com.springshop.common.excel;

import java.util.List;

/**
 * Excel 数据行（表头之外的一行）
 *
 * <p>{@code rowNum} 与用户在 Excel 中看到的行号一致（第 1 行是表头，数据行从 2 开始），
 * 这样导入失败时提示「第 5 行 xxx 不合法」用户能直接定位。
 */
public class ExcelRow {

    /** 行号，与 Excel 界面显示的行号一致 */
    private final int rowNum;

    /** 单元格文本（已 trim），列不存在时按空串处理 */
    private final List<String> cells;

    public ExcelRow(int rowNum, List<String> cells) {
        this.rowNum = rowNum;
        this.cells = cells;
    }

    /**
     * 取第 index 列文本，列不存在或为空白时返回空串（避免调用方到处判 null）
     */
    public String cell(int index) {
        if (index < 0 || index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value;
    }

    public int getRowNum() {
        return rowNum;
    }

    public List<String> getCells() {
        return cells;
    }
}
