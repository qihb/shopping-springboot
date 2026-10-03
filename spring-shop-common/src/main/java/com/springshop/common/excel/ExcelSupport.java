package com.springshop.common.excel;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 读写通用工具
 *
 * <p>只负责「字节流 ↔ 字符串表格」的转换，不感知任何业务语义：
 * 表头是否正确、字段是否必填、数值是否越界等校验全部由各业务模块自己完成。
 * 这样商品导入与管理员导入可以复用同一份 POI 解析代码，避免重复实现。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>统一把单元格读成字符串（{@link DataFormatter}），业务侧再按需转数字，
 *       这样模板里「文本格式的数字」也能被正确识别，不会因为单元格格式不同而丢值；</li>
 *   <li>解析失败抛出 {@link IOException}，调用方统一转成自己的业务错误码，
 *       避免 common 依赖任何业务枚举。</li>
 * </ul>
 */
public final class ExcelSupport {

    /** 数据行数上限：超过即拒绝，防止超大文件拖垮应用 */
    public static final int MAX_ROWS = 1000;

    /** 单行最大列数：防止异常宽表导致的内存放大 */
    private static final int MAX_COLS = 64;

    /** 模板列宽（字符数） */
    private static final int COLUMN_WIDTH = 20;

    private ExcelSupport() {
    }

    /**
     * 读取首个工作表的数据行（跳过第 1 行表头）
     *
     * @param in      文件输入流，由调用方负责关闭
     * @param maxRows 允许的最大数据行数
     * @return 数据行列表，行号与用户在 Excel 中看到的行号一致
     * @throws ExcelReadException 文件不可解析、无数据行或行数超限
     */
    public static List<ExcelRow> read(InputStream in, int maxRows) throws ExcelReadException {
        Workbook workbook = openWorkbook(in);
        try (workbook) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ExcelReadException("Excel 中没有任何工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<ExcelRow> rows = new ArrayList<>();

            // 第 0 行固定为表头，数据从第 1 行开始
            int lastRowNum = sheet.getLastRowNum();
            for (int i = 1; i <= lastRowNum; i++) {
                List<String> cells = readCells(sheet.getRow(i), formatter);
                if (isBlank(cells)) {
                    // 跳过空行：用户在表格尾部多按几次回车会产生大量空行
                    continue;
                }
                if (rows.size() >= maxRows) {
                    throw new ExcelReadException("数据行数超过上限 " + maxRows + " 行，请拆分后分批导入");
                }
                rows.add(new ExcelRow(i + 1, cells));
            }

            if (rows.isEmpty()) {
                throw new ExcelReadException("文件中没有可导入的数据行");
            }
            return rows;
        } catch (ExcelReadException e) {
            // 我们自己的可读提示原样抛出，不要被下面的兜底分支再包一层
            throw e;
        } catch (IOException e) {
            throw new ExcelReadException("文件读取失败，请重新导出后重试", e);
        }
    }

    /**
     * 打开工作簿，并把 POI 的底层异常收敛成可读提示
     */
    private static Workbook openWorkbook(InputStream in) throws ExcelReadException {
        try {
            return WorkbookFactory.create(in);
        } catch (EncryptedDocumentException e) {
            throw new ExcelReadException("文件已加密，请取消密码保护后重新导出", e);
        } catch (IOException | RuntimeException e) {
            throw new ExcelReadException("文件无法解析，请确认使用模板中的 xlsx / xls 格式", e);
        }
    }

    /**
     * 生成单工作表 xlsx 字节数组（表头加粗并带底色）
     *
     * @param sheetName 工作表名
     * @param headers   表头
     * @param rows      数据行
     */
    public static byte[] write(String sheetName, List<String> headers, List<List<String>> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);

            CellStyle headerStyle = buildHeaderStyle(workbook);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, COLUMN_WIDTH * 256);
            }

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    String value = cells.get(c);
                    row.createCell(c).setCellValue(value == null ? "" : value);
                }
            }

            workbook.write(out);
            return out.toByteArray();
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

    private static List<String> readCells(Row row, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        if (row == null) {
            return cells;
        }
        int lastCellNum = Math.min(row.getLastCellNum(), MAX_COLS);
        for (int c = 0; c < lastCellNum; c++) {
            Cell cell = row.getCell(c);
            String value = cell == null ? "" : formatter.formatCellValue(cell);
            cells.add(value == null ? "" : value.trim());
        }
        return cells;
    }

    private static boolean isBlank(List<String> cells) {
        for (String cell : cells) {
            if (!isBlankText(cell)) {
                return false;
            }
        }
        return true;
    }

    private static CellStyle buildHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
