package com.springshop.common.excel;

import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.enums.ReadDefaultReturnEnum;
import org.apache.fesod.sheet.exception.ExcelAnalysisException;
import org.apache.fesod.sheet.exception.ExcelGenerateException;
import org.apache.fesod.sheet.read.builder.ExcelReaderBuilder;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.fesod.sheet.support.ExcelTypeEnum;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.fesod.sheet.write.style.column.LongestMatchColumnWidthStyleStrategy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Excel 读写通用工具（基于 Apache Fesod）
 *
 * <p>只负责「字节流 ↔ 字符串表格」的转换，不感知任何业务语义：
 * 表头是否正确、字段是否必填、数值是否越界等校验全部由各业务模块自己完成。
 *
 * <p><b>为什么从 POI 换成 Fesod</b>：POI 的 {@code WorkbookFactory.create} 会把整个工作簿
 * 解析成 DOM 树驻留堆内存，一万行的 xlsx 轻松吃掉几百 MB，导入上限只能卡在千行级。
 * Fesod（原 EasyExcel 的 Apache 孵化版）读走 SAX 事件流、逐行回调，写按批刷缓存，
 * 内存占用与文件行数基本无关，因此才敢把上限放开到万行以上。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>统一把单元格读成字符串（{@code readDefaultReturn(STRING)}），业务侧再按需转数字，
 *       这样模板里「文本格式的数字」也能被正确识别，不会因为单元格格式不同而丢值；</li>
 *   <li>读取以回调方式逐行吐出（{@link #streamRead}），调用方可以边读边处理，
 *       不需要先把全部行堆成 List；确实需要全量（如商品导入要按名称聚合）时用
 *       {@link #readAll} 的语法糖；</li>
 *   <li>解析失败抛出 {@link ExcelReadException}，调用方统一转成自己的业务错误码，
 *       避免 common 依赖任何业务枚举。</li>
 * </ul>
 */
public final class ExcelSupport {

    private ExcelSupport() {
    }

    /**
     * 流式读取首个工作表的数据行（跳过表头），每读一行回调一次
     *
     * <p>SAX 事件流逐行解析，调用方在回调里即时处理（校验、攒批落库），
     * 不需要把整个文件读进内存。
     *
     * @param in          文件输入流，由调用方负责关闭
     * @param options     读取参数（sheet 序号、表头行数、行数上限、列数上限）
     * @param rowConsumer 行回调，{@code rowNum} 与用户在 Excel 中看到的行号一致
     * @return 实际读到的数据行数（已跳过空行）
     * @throws ExcelReadException 文件不可解析、无数据行或行数超限
     */
    public static int streamRead(InputStream in, ExcelReadOptions options, Consumer<ExcelRow> rowConsumer)
            throws ExcelReadException {
        if (in == null) {
            throw new ExcelReadException("文件内容为空，请重新导出后重试");
        }
        RowCollectingListener listener = new RowCollectingListener(options, rowConsumer);
        try {
            ExcelReaderBuilder builder = FesodSheet.read(in, listener)
                    .headRowNumber(options.getHeadRowNumber())
                    .autoTrim(true)
                    // 尾部空行（用户在表格末尾多按回车）直接由框架丢弃，不用业务侧再判一次
                    .ignoreEmptyRow(true)
                    .readDefaultReturn(ReadDefaultReturnEnum.STRING);
            if (options.getFileType() != null) {
                builder.excelType(toFesodType(options.getFileType()));
            }
            builder.sheet(options.getSheetNo()).doRead();
        } catch (Exception e) {
            throw toReadException(e);
        }
        if (listener.getRowCount() == 0) {
            throw new ExcelReadException("文件中没有可导入的数据行");
        }
        return listener.getRowCount();
    }

    /**
     * 读取全部数据行到内存
     *
     * <p>适合「需要跨行聚合」的场景（如商品导入按商品名称把多行合并成一个 SPU）。
     * 纯逐行处理请优先用 {@link #streamRead}，避免把上万行都堆在堆里。
     */
    public static List<ExcelRow> readAll(InputStream in, ExcelReadOptions options) throws ExcelReadException {
        List<ExcelRow> rows = new ArrayList<>();
        streamRead(in, options, rows::add);
        return rows;
    }

    /**
     * 生成单工作表 xlsx 字节数组（表头加粗带底色、列宽自适应）
     *
     * @param sheetName 工作表名
     * @param headers   表头（单行）
     * @param rows      数据行，行内元素个数需与表头一致
     */
    public static byte[] writeDynamic(String sheetName, List<String> headers, List<List<String>> rows)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeDynamic(out, sheetName, headers, rows);
        return out.toByteArray();
    }

    /**
     * 写出单工作表 xlsx（流式，适合导出结果直接落盘）
     *
     * @param out       输出流，本方法只 flush 不 close，由调用方负责
     */
    public static void writeDynamic(OutputStream out, String sheetName, List<String> headers, List<List<String>> rows)
            throws IOException {
        try (ExcelStreamWriter writer = ExcelStreamWriter.ofHeaders(out, headers, sheetName)) {
            writer.write(rows);
        } catch (ExcelGenerateException e) {
            throw new IOException("Excel 生成失败", e);
        }
    }

    /**
     * 解析数字单元格：空白返回 null，格式非法抛出 {@link IllegalArgumentException}
     */
    public static BigDecimal parseDecimal(String text) {
        if (isBlankText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("「" + text + "」不是合法数字");
        }
    }

    /**
     * 解析整数单元格：空白返回 null，格式非法抛出 {@link IllegalArgumentException}
     */
    public static Integer parseInt(String text) {
        if (isBlankText(text)) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("「" + text + "」不是合法整数");
        }
    }

    /**
     * 文本是否为空（null 或仅空白）
     */
    public static boolean isBlankText(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 把 Fesod / POI 的底层异常收敛成可读提示
     *
     * <p>不做「原样透出」是因为底层异常文本对运营毫无意义
     * （如 "Your InputStream was neither an OLE2 stream, nor an OOXML stream"）。
     */
    private static ExcelReadException toReadException(Exception e) {        RowLimitException limit = findCause(e, RowLimitException.class);
        if (limit != null) {
            return new ExcelReadException(limit.getMessage());
        }
        ExcelReadException readException = findCause(e, ExcelReadException.class);
        if (readException != null) {
            return readException;
        }
        if (findCause(e, "Encrypted") != null) {
            return new ExcelReadException("文件已加密，请取消密码保护后重新导出", e);
        }
        if (findCause(e, "Password") != null || findCause(e, "password") != null) {
            return new ExcelReadException("文件已加密，请取消密码保护后重新导出", e);
        }
        return new ExcelReadException("文件无法解析，请确认使用模板中的 xlsx / xls 格式", e);
    }

    private static ExcelTypeEnum toFesodType(ExcelFileType fileType) {
        return fileType == ExcelFileType.XLS ? ExcelTypeEnum.XLS : ExcelTypeEnum.XLSX;
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        Throwable current = e;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 按类名关键字找原因：POI 的加密异常类名含 "Encrypted"，这里不直接 import
     * POI 类型，避免 common 与 POI 产生编译期耦合。
     */
    private static Throwable findCause(Throwable e, String classNameKeyword) {
        Throwable current = e;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current.getClass().getSimpleName().contains(classNameKeyword)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 逐行收集监听器
     *
     * <p>Fesod 在无 head 类时把每行喂成 {@code Map<Integer, String>}（列下标 → 文本），
     * 这里再转成业务侧统一的 {@link ExcelRow}。
     */
    private static final class RowCollectingListener implements ReadListener<Map<Integer, String>> {

        private final ExcelReadOptions options;

        private final Consumer<ExcelRow> rowConsumer;

        private int rowCount;

        private RowCollectingListener(ExcelReadOptions options, Consumer<ExcelRow> rowConsumer) {
            this.options = options;
            this.rowConsumer = rowConsumer;
        }

        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            List<String> cells = toCells(data);
            if (isBlank(cells)) {
                return;
            }
            if (rowCount >= options.getMaxRows()) {
                // 抛异常而不是 context.interrupt()：需要把「超限」这个具体原因带回调用方，
                // interrupt 只会得到一个笼统的「分析被中断」
                throw new RowLimitException("数据行数超过上限 " + options.getMaxRows() + " 行，请拆分后分批导入");
            }
            rowCount++;
            // rowIndex 从 0 开始，而用户在 Excel 里看到的是从 1 开始的行号，+1 后两者一致
            rowConsumer.accept(new ExcelRow(context.readRowHolder().getRowIndex() + 1, cells));
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 无需收尾动作：调用方通过返回值拿到行数
        }

        @Override
        public void onException(Exception exception, AnalysisContext context) {
            // 默认实现会吞掉异常，这里必须重新抛出，否则「行数超限」「格式非法」都会被静默忽略
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ExcelAnalysisException(exception);
        }

        private int getRowCount() {
            return rowCount;
        }

        private List<String> toCells(Map<Integer, String> data) {
            List<String> cells = new ArrayList<>();
            if (data == null || data.isEmpty()) {
                return cells;
            }
            int maxIndex = -1;
            for (Integer index : data.keySet()) {
                if (index != null && index > maxIndex) {
                    maxIndex = index;
                }
            }
            int limit = Math.min(maxIndex + 1, options.getMaxColumns());
            for (int i = 0; i < limit; i++) {
                String value = data.get(i);
                cells.add(value == null ? "" : value.trim());
            }
            return cells;
        }

        private boolean isBlank(List<String> cells) {
            for (String cell : cells) {
                if (!isBlankText(cell)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 行数超限的内部信号：需要穿透 Fesod 的异常包装回到 {@link #streamRead}
     */
    private static final class RowLimitException extends RuntimeException {

        private RowLimitException(String message) {
            super(message);
        }
    }
}
