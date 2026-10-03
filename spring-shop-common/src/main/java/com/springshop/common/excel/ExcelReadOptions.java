package com.springshop.common.excel;

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
}
