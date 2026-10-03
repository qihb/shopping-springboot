package com.springshop.common.excel;

import java.util.Locale;

/**
 * 支持的 Excel 文件类型
 *
 * <p>存在的意义是<b>把文件类型判断从各业务模块收口到一处</b>：导入功能原先在商品与管理员
 * 两处各写了一份 {@code endsWith(".xlsx")}，且判断完之后没有把结论传给解析器。
 *
 * <p>为什么要显式把类型传给解析器：Fesod 的 {@code excelType} 默认是「自动探测」，
 * 探测失败时会**退化按 CSV 解析**。于是一个改名的 csv 或者损坏的 xlsx 不会报错，
 * 只会解析出 0 行数据，用户看到的是「文件里没有可导入的数据行」这种莫名其妙的提示。
 * 强制指定类型后，损坏文件会直接抛出解析异常，我们才能给出「文件无法解析」的准确话术。
 */
public enum ExcelFileType {

    /** Office 2007+ 的 xlsx（OOXML，zip 容器） */
    XLSX,

    /** Office 97-2003 的 xls（OLE2 容器） */
    XLS;

    /**
     * 按文件名后缀判断类型
     *
     * @return 无法识别时返回 null，由调用方给出「仅支持 .xlsx / .xls」的业务提示
     */
    public static ExcelFileType fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return XLSX;
        }
        if (lower.endsWith(".xls")) {
            return XLS;
        }
        return null;
    }
}
