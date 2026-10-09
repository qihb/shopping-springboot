package com.springshop.common.excel;

import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Excel 读写工具单测
 *
 * <p>覆盖三件事：写出去的能读回来（读写口径一致）、异常话术可读、上万行不越界。
 * 用真实字节流往返，不 mock Fesod——这里要验证的正是「与 Fesod 的对接是否成立」。
 */
class ExcelSupportTest {

    /** 测试里生成的字节流都是 xlsx；显式指定类型，行为与业务侧一致 */
    private static ExcelReadOptions xlsxOptions() {
        return ExcelReadOptions.defaults().fileType(ExcelFileType.XLSX);
    }

    /**
     * 模拟一份商品导入模板的表头
     *
     * <p>刻意包含「规格」这种位于中间的列：删掉它会让它后面所有列整体左移，
     * 是表头校验要拦住的最典型事故。
     */
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "商品名称*", "副标题", "主图URL", "分类名称*", "SKU编码*", "规格",
            "销售价*", "原价", "库存", "状态(1上架/0下架)");

    /** 用指定表头 + 单行数据生成一个 xlsx，用于验证表头校验 */
    private static byte[] sheetWith(List<String> headers, List<String> row) throws IOException {
        return ExcelSupport.writeDynamic("模板", headers, List.of(row));
    }

    @Test
    @DisplayName("动态表头：写出的模板能原样读回，行号从 2 开始且跳过空行")
    void dynamicWriteThenRead() throws IOException {
        List<List<String>> rows = List.of(
                List.of("示例商品A", "199.00", "1"),
                List.of("示例商品B", "299.00", "0"));

        byte[] bytes = ExcelSupport.writeDynamic("模板", List.of("名称", "价格", "状态"), rows);
        assertThat(bytes).isNotEmpty();

        List<ExcelRow> read = ExcelSupport.readAll(new ByteArrayInputStream(bytes), xlsxOptions());

        assertThat(read).hasSize(2);
        // 第 1 行是表头，所以第一条数据是「第 2 行」，用户能照着行号定位问题
        assertThat(read.get(0).getRowNum()).isEqualTo(2);
        assertThat(read.get(0).cell(0)).isEqualTo("示例商品A");
        assertThat(read.get(0).cell(1)).isEqualTo("199.00");
        assertThat(read.get(1).getRowNum()).isEqualTo(3);
        assertThat(read.get(1).cell(0)).isEqualTo("示例商品B");
    }

    @Test
    @DisplayName("表头是列优先：3 列表头写成 1 行 3 列，而不是 3 行 1 列")
    void headerIsWrittenAsSingleRow() throws IOException {
        byte[] bytes = ExcelSupport.writeDynamic("模板", List.of("名称", "价格", "状态"),
                List.of(List.of("A", "1", "2")));

        List<String> headers = new ArrayList<>();
        ExcelSupport.streamRead(new ByteArrayInputStream(bytes),
                xlsxOptions().headRowNumber(0),
                row -> headers.add(row.cell(0) + "/" + row.cell(1) + "/" + row.cell(2)));

        assertThat(headers).containsExactly("名称/价格/状态", "A/1/2");
    }

    @Test
    @DisplayName("流式读取：逐行回调，缺列按空串补齐")
    void streamReadSkipsTrailingBlankCells() throws IOException {
        byte[] bytes = ExcelSupport.writeDynamic("模板", List.of("A", "B", "C"),
                List.of(List.of("1", "2", "3"), List.of("4", "", "")));

        List<String> firstCells = new ArrayList<>();
        int count = ExcelSupport.streamRead(new ByteArrayInputStream(bytes), xlsxOptions(),
                row -> firstCells.add(row.cell(0) + "|" + row.cell(2)));

        assertThat(count).isEqualTo(2);
        assertThat(firstCells).containsExactly("1|3", "4|");
    }

    @Test
    @DisplayName("行数超限：抛出可读提示而不是让 POI/Fesod 的原始异常透出")
    void rowLimitExceeded() throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(List.of("第" + i + "行"));
        }
        byte[] bytes = ExcelSupport.writeDynamic("模板", List.of("名称"), rows);

        assertThatThrownBy(() -> ExcelSupport.readAll(
                new ByteArrayInputStream(bytes), xlsxOptions().maxRows(3)))
                .isInstanceOf(ExcelReadException.class)
                .hasMessageContaining("超过上限 3 行");
    }

    @Test
    @DisplayName("空数据：只有表头时提示「没有可导入的数据行」")
    void noDataRows() throws IOException {
        byte[] bytes = ExcelSupport.writeDynamic("模板", List.of("名称"), List.of());

        assertThatThrownBy(() -> ExcelSupport.readAll(new ByteArrayInputStream(bytes), xlsxOptions()))
                .isInstanceOf(ExcelReadException.class)
                .hasMessageContaining("没有可导入的数据行");
    }

    // ---------------- 表头校验 ----------------
    //
    // 这一组是「按列下标取值」的前置条件：表头一旦对不上，取值就整体错位，
    // 而错位后的值往往照样能通过类型与范围校验，最终把数据写进另一个字段且任务报「成功」。
    // 所以这里既要有「一致就放行」，也要有「删列 / 改名 / 没表头都必须拒绝」。

    @Test
    @DisplayName("表头校验：与模板一致时正常读取")
    void headerMatches_shouldReadNormally() throws IOException {
        byte[] bytes = sheetWith(TEMPLATE_HEADERS, List.of(
                "商品A", "副标题", "https://example.com/a.jpg", "手机", "SKU-1", "颜色:黑",
                "199.00", "299.00", "10", "1"));

        List<ExcelRow> rows = ExcelSupport.readAll(new ByteArrayInputStream(bytes),
                xlsxOptions().expectedHeaders(TEMPLATE_HEADERS));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cell(4)).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName("表头校验：删掉中间一列必须当场失败，并指出是哪一列开始错位")
    void deletedColumn_shouldFailWithExactPosition() throws IOException {
        List<String> tampered = new ArrayList<>(TEMPLATE_HEADERS);
        tampered.remove(5); // 删掉「规格」

        byte[] bytes = sheetWith(tampered, List.of("商品A"));

        assertThatThrownBy(() -> ExcelSupport.readAll(new ByteArrayInputStream(bytes),
                xlsxOptions().expectedHeaders(TEMPLATE_HEADERS)))
                .isInstanceOf(ExcelReadException.class)
                .hasMessageContaining("第 6 列")
                .hasMessageContaining("规格")
                .hasMessageContaining("销售价*");
    }

    @Test
    @DisplayName("表头校验：删掉最后一列也要拒绝（该列有默认值，静默生效比报错更危险）")
    void missingTrailingColumn_shouldFail() throws IOException {
        List<String> tampered = new ArrayList<>(TEMPLATE_HEADERS);
        tampered.remove(TEMPLATE_HEADERS.size() - 1); // 删掉「状态(1上架/0下架)」

        byte[] bytes = sheetWith(tampered, List.of("商品A"));

        assertThatThrownBy(() -> ExcelSupport.readAll(new ByteArrayInputStream(bytes),
                xlsxOptions().expectedHeaders(TEMPLATE_HEADERS)))
                .isInstanceOf(ExcelReadException.class)
                .hasMessageContaining("第 10 列")
                .hasMessageContaining("状态");
    }

    @Test
    @DisplayName("表头校验：改了列名同样失败")
    void renamedColumn_shouldFail() throws IOException {
        List<String> tampered = new ArrayList<>(TEMPLATE_HEADERS);
        tampered.set(1, "副标题(选填)");

        byte[] bytes = sheetWith(tampered, List.of("商品A"));

        assertThatThrownBy(() -> ExcelSupport.readAll(new ByteArrayInputStream(bytes),
                xlsxOptions().expectedHeaders(TEMPLATE_HEADERS)))
                .isInstanceOf(ExcelReadException.class)
                .hasMessageContaining("第 2 列")
                .hasMessageContaining("副标题");
    }

    @Test
    @DisplayName("表头校验：末尾多出额外的列不影响按下标取值，放行")
    void extraTrailingColumn_shouldStillRead() throws IOException {
        List<String> withExtra = new ArrayList<>(TEMPLATE_HEADERS);
        withExtra.add("备注");

        byte[] bytes = sheetWith(withExtra, List.of("商品A"));

        List<ExcelRow> rows = ExcelSupport.readAll(new ByteArrayInputStream(bytes),
                xlsxOptions().expectedHeaders(TEMPLATE_HEADERS));

        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("表头校验：第 1 行没有表头时拒绝，而不是把第一条数据当普通数据导进去")
    void blankHeaderRow_shouldFail() throws IOException {
        byte[] bytes = sheetWith(List.of("", "", ""), List.of("商品A", "副标题", "https://example.com/a.jpg"));

        // 空表头行可能被 Fesod 当空行丢掉（走「第 1 行没有表头」这条分支），
        // 也可能以「全空单元格」的形式回调到表头校验（走「第 1 列实际为空」这条分支）。
        // 两条路径的提示不同，但都必须以「表头」为关键词明确拒绝。
        assertThatThrownBy(() -> ExcelSupport.readAll(new ByteArrayInputStream(bytes),
                xlsxOptions().expectedHeaders(TEMPLATE_HEADERS)))
                .isInstanceOf(ExcelReadException.class)
                .hasMessageContaining("表头");
    }

    @Test
    @DisplayName("未声明模板表头时不做校验：回读导出结果等场景必须保持原行为")
    void withoutExpectedHeaders_shouldNotValidate() throws IOException {
        byte[] bytes = sheetWith(List.of("任意表头", "第二列"), List.of("A", "B"));

        List<ExcelRow> rows = ExcelSupport.readAll(new ByteArrayInputStream(bytes), xlsxOptions());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cell(0)).isEqualTo("A");
    }

    @Test
    @DisplayName("非 Excel 内容：指定 xlsx 类型后不会退化成 CSV，而是明确报「无法解析」")
    void unparsableContent() {
        byte[] garbage = "this is definitely not a spreadsheet".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ExcelSupport.readAll(new ByteArrayInputStream(garbage), xlsxOptions()))
                .isInstanceOf(ExcelReadException.class)
                .hasMessageContaining("文件无法解析");
    }

    @Test
    @DisplayName("文件类型识别：只认 xlsx / xls，其余返回 null")
    void detectFileType() {
        assertThat(ExcelFileType.fromFileName("商品导入.xlsx")).isEqualTo(ExcelFileType.XLSX);
        assertThat(ExcelFileType.fromFileName("ADMIN.XLS")).isEqualTo(ExcelFileType.XLS);
        assertThat(ExcelFileType.fromFileName("名单.csv")).isNull();
        assertThat(ExcelFileType.fromFileName(null)).isNull();
    }

    @Test
    @DisplayName("流式写出：分批 write 与一次性 write 结果一致（导出大数据的核心路径）")
    void streamWriterSupportsBatches() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelStreamWriter writer = ExcelStreamWriter.ofClass(out, DemoRow.class, "示例")) {
            writer.write(List.of(new DemoRow("甲", "1.00"), new DemoRow("乙", "2.00")));
            writer.write(List.of(new DemoRow("丙", "3.00")));
            // 空批不应产生额外行，也不应把表头写两遍
            writer.write(List.of());
            writer.write(null);
            assertThat(writer.getWritten()).isEqualTo(3);
        }

        List<ExcelRow> read = ExcelSupport.readAll(new ByteArrayInputStream(out.toByteArray()), xlsxOptions());
        assertThat(read).hasSize(3);
        assertThat(read).extracting(row -> row.cell(0)).containsExactly("甲", "乙", "丙");
        assertThat(read).extracting(row -> row.cell(1)).containsExactly("1.00", "2.00", "3.00");
    }

    @Test
    @DisplayName("流式写出：动态表头（失败明细/导出结果）")
    void streamWriterWithDynamicHeaders() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelStreamWriter writer = ExcelStreamWriter.ofHeaders(out, List.of("行号", "失败原因"), "失败明细")) {
            writer.write(List.of(List.of("5", "SKU 编码不能为空")));
        }

        List<ExcelRow> read = ExcelSupport.readAll(new ByteArrayInputStream(out.toByteArray()), xlsxOptions());
        assertThat(read).hasSize(1);
        assertThat(read.get(0).cell(1)).isEqualTo("SKU 编码不能为空");
    }

    @Test
    @DisplayName("单元格解析：空白返回 null，非法值抛出可读提示")
    void parseHelpers() {
        assertThat(ExcelSupport.parseDecimal(" 12.30 ")).isEqualByComparingTo(new BigDecimal("12.30"));
        assertThat(ExcelSupport.parseDecimal("")).isNull();
        assertThat(ExcelSupport.parseDecimal(null)).isNull();
        assertThatThrownBy(() -> ExcelSupport.parseDecimal("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法数字");

        assertThat(ExcelSupport.parseInt("42")).isEqualTo(42);
        assertThat(ExcelSupport.parseInt("  ")).isNull();
        assertThatThrownBy(() -> ExcelSupport.parseInt("4.2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法整数");

        assertThat(ExcelSupport.isBlankText(null)).isTrue();
        assertThat(ExcelSupport.isBlankText(" \t ")).isTrue();
        assertThat(ExcelSupport.isBlankText("x")).isFalse();
    }

    /**
     * 导出模型示例：验证「注解模型 + 分批写」这条导出主链路
     */
    public static class DemoRow {

        @ExcelProperty("名称")
        private String name;

        @ExcelProperty("金额")
        private String amount;

        public DemoRow() {
        }

        public DemoRow(String name, String amount) {
            this.name = name;
            this.amount = amount;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }
    }
}
