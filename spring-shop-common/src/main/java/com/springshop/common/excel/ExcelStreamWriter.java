package com.springshop.common.excel;

import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.fesod.sheet.write.style.column.LongestMatchColumnWidthStyleStrategy;

import java.io.Closeable;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Excel 流式写出器
 *
 * <p><b>解决的问题</b>：导出十万行时，如果先把全部行拼成一个 {@code List} 再写，
 * 光业务对象 + 单元格文本就要几百 MB；如果每一批都新建一个 writer，
 * 又会把 xlsx 的 zip 流反复重写。正确做法是「一个 writer + 一个 sheet，
 * 反复 {@code write(批)}」，Fesod 内部按 100 行一刷缓存，内存占用与总行数无关。
 *
 * <p>用法固定为三段式：
 * <pre>{@code
 * try (ExcelStreamWriter writer = ExcelStreamWriter.ofClass(out, OrderExportRow.class, "订单")) {
 *     while (还有数据) {
 *         writer.write(下一页数据);   // 每页 5000 行，内存恒定
 *     }
 * }
 * }</pre>
 *
 * <p>注意：{@code autoCloseStream(false)}，本类不会关闭调用方传入的 {@link OutputStream}，
 * 因为导出场景下这个流往往是 Servlet 响应流或由存储层托管的文件流。
 */
public final class ExcelStreamWriter implements Closeable {

    private final ExcelWriter writer;

    private final WriteSheet writeSheet;

    private int written;

    private boolean closed;

    private ExcelStreamWriter(ExcelWriter writer, WriteSheet writeSheet) {
        this.writer = writer;
        this.writeSheet = writeSheet;
    }

    /**
     * 按注解模型写出（模型字段用 {@code @ExcelProperty} 标注列名）
     *
     * @param out       输出流，本类只 flush 不 close
     * @param headClass 表头模型类
     * @param sheetName 工作表名
     */
    public static ExcelStreamWriter ofClass(OutputStream out, Class<?> headClass, String sheetName) {
        ExcelWriter writer = FesodSheet.write(out, headClass)
                .autoCloseStream(false)
                .useDefaultStyle(true)
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .build();
        return new ExcelStreamWriter(writer, FesodSheet.writerSheet(sheetName).build());
    }

    /**
     * 按动态表头写出（没有模型类时用，如导入模板与失败明细）
     *
     * @param headers 单行表头
     */
    public static ExcelStreamWriter ofHeaders(OutputStream out, List<String> headers, String sheetName) {
        ExcelWriter writer = FesodSheet.write(out)
                .head(toColumnMajorHead(headers))
                .autoCloseStream(false)
                .useDefaultStyle(true)
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .build();
        return new ExcelStreamWriter(writer, FesodSheet.writerSheet(sheetName).build());
    }

    /**
     * 把「一行表头」转成 Fesod 要求的「按列组织的表头」
     *
     * <p>Fesod 的 {@code head(List<List<String>>)} 是<b>列优先</b>的：外层下标是列，
     * 内层是该列自上而下的多级表头。直接把 {@code List.of(headers)} 传进去，
     * 会被理解成「第 1 列有 3 级表头」，写出来是 3 行 × 1 列而不是 1 行 × 3 列
     * （实测确认，是个很容易踩且不易察觉的坑：数据行仍然正确，只有表头错位）。
     */
    private static List<List<String>> toColumnMajorHead(List<String> headers) {
        List<List<String>> head = new ArrayList<>(headers.size());
        for (String header : headers) {
            head.add(List.of(header));
        }
        return head;
    }

    /**
     * 追加一批数据
     *
     * <p>批大小建议 2000~5000：太小会让 xlsx 的 zip 流频繁 flush，太大则失去流式意义。
     *
     * <p>注意：<b>空集合也要透传</b>。Fesod 是在第一次 {@code write} 时才创建 sheet 并写表头的，
     * 若把空批直接丢掉，「查询结果 0 行」的导出会生成一个连表头都没有的损坏文件
     * （读回来直接报 "Can not find any sheet!"），用户拿到文件打开是空白页、无从判断是没数据还是导出失败。
     *
     * @param rows 数据行；元素类型需与构造时的模型一致（动态表头时元素为 {@code List<String>}）
     */
    public void write(Collection<?> rows) {
        if (rows == null) {
            return;
        }
        writer.write(rows, writeSheet);
        written += rows.size();
    }

    /**
     * 已写出的数据行数（不含表头）
     */
    public int getWritten() {
        return written;
    }

    /**
     * 收尾：{@code ExcelWriter.close()} 内部即 {@code finish()}，负责把缓存刷进输出流。
     * 只调用一次，重复调用会被本类的 {@code closed} 标志挡掉。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        writer.close();
    }
}
