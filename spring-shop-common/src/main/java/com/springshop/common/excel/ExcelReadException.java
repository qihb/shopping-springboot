package com.springshop.common.excel;

import java.io.IOException;

/**
 * Excel 读取失败异常
 *
 * <p>单独定义是为了让调用方能把「文件为空 / 行数超限 / 格式不对」这类
 * 可读提示原样透出给前端，而把 POI 的底层异常统一收敛成一句人话，
 * 避免把 "Your InputStream was neither an OLE2 stream, nor an OOXML stream" 抛给用户。
 */
public class ExcelReadException extends IOException {

    public ExcelReadException(String message) {
        super(message);
    }

    public ExcelReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
