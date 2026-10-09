package com.springshop.common.excel;

import java.util.List;

/**
 * Excel 读取参数
 *
 * <p>把「读第几个 sheet、表头几行、最多读多少行、最多读多少列」这些纯技术参数收口成对象，
 * 避免每个导入功能各写一串魔法数字。业务语义（哪些列必填、数值范围）不在这里。
 */
public class ExcelReadOptions {

    /** 默认数据行上限：够覆盖「上万条」的量级，同时挡住恶意/误传的超大文件 */
    public static final int DEFAULT_MAX_ROWS = 100_000;

    /** 单行最大列数：防止异常宽表导致的内存放大 */
    private int maxColumns = 64;

    /** 数据行数上限，超过即中断读取并报错 */
    private int maxRows = DEFAULT_MAX_ROWS;

    /** 工作表序号，从 0 开始 */
    private int sheetNo = 0;

    /** 表头行数：模板都是单行表头，数据从第 2 行开始 */
    private int headRowNumber = 1;

    /** 文件类型：显式指定可避免 Fesod 探测失败后退化成 CSV 解析（详见 {@link ExcelFileType}） */
    private ExcelFileType fileType;

    /**
     * 期望的表头（模板表头，按列顺序）
     *
     * <p>为空表示不做表头校验（导出结果的回读、通用表格解析等场景）。
     * 非空时按列逐字比对，不一致直接失败——见 {@link #expectedHeaders(List)}。
     */
    private List<String> expectedHeaders;

    public static ExcelReadOptions defaults() {
        return new ExcelReadOptions();
    }

    public static ExcelReadOptions withMaxRows(int maxRows) {
        return new ExcelReadOptions().maxRows(maxRows);
    }

    public int getMaxColumns() {
        return maxColumns;
    }

    public ExcelReadOptions maxColumns(int maxColumns) {
        this.maxColumns = maxColumns;
        return this;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public ExcelReadOptions maxRows(int maxRows) {
        this.maxRows = maxRows;
        return this;
    }

    public int getSheetNo() {
        return sheetNo;
    }

    public ExcelReadOptions sheetNo(int sheetNo) {
        this.sheetNo = sheetNo;
        return this;
    }

    public int getHeadRowNumber() {
        return headRowNumber;
    }

    public ExcelReadOptions headRowNumber(int headRowNumber) {
        this.headRowNumber = headRowNumber;
        return this;
    }

    public ExcelFileType getFileType() {
        return fileType;
    }

    public ExcelReadOptions fileType(ExcelFileType fileType) {
        this.fileType = fileType;
        return this;
    }

    /**
     * 按文件名推断并设置文件类型；无法识别时保持 null（解析器退回自动探测）
     */
    public ExcelReadOptions fileTypeFromName(String fileName) {
        this.fileType = ExcelFileType.fromFileName(fileName);
        return this;
    }

    public List<String> getExpectedHeaders() {
        return expectedHeaders;
    }

    /**
     * 声明模板表头，读取时逐列比对，不一致立即失败
     *
     * <p><b>为什么必须校验表头</b>：业务侧是按「列下标」取值的
     * （{@code row.cell(COL_PRICE)} 之类）。只要用户删掉或挪动了一列，
     * 下标与语义的对应关系就整体错位，而后面的取值、类型转换、范围校验
     * <b>全都能正常通过</b>——最终把「销售价」写进了「规格」、「库存」写进了「状态」，
     * 任务还报「成功」。这类事故没有任何报错信号，只能靠表头这道闸拦住。
     *
     * <p>比对规则（刻意从严）：
     * <ul>
     *   <li>按列下标逐字比对（去掉首尾空白），<b>少列、错位、改名都会被拒绝</b>；</li>
     *   <li>允许文件在模板最后一列之后多出额外的列：多出来的列不影响按下标取值，
     *       为此拒绝用户「顺手加了备注列」的文件没有意义；</li>
     *   <li>第 1 行为空（表头被整行删掉）同样拒绝，不会退化成「把第一条数据当表头」。</li>
     * </ul>
     *
     * <p>失败提示会指出<b>第一处</b>不一致的列号、期望值与实际值，
     * 让运营能自己定位，而不是丢一句「文件格式不对」。
     *
     * @param expectedHeaders 模板表头，顺序必须与业务侧取值的列下标一致；
     *                        传 {@code null} 或空列表表示不做校验
     */
    public ExcelReadOptions expectedHeaders(List<String> expectedHeaders) {
        this.expectedHeaders = expectedHeaders;
        return this;
    }

    /**
     * 是否声明了模板表头（即是否需要做表头校验）
     */
    public boolean hasExpectedHeaders() {
        return expectedHeaders != null && !expectedHeaders.isEmpty();
    }
}
