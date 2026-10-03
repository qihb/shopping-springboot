package com.springshop.common.excel.task;

/**
 * Excel 任务状态
 *
 * <p>只有四个状态，刻意不做「部分成功」：导入的「部分成功」信息由
 * {@code successRows / failRows} 两个计数 + 失败明细表达，
 * 状态只回答「这次任务跑完了没有、有没有整体失败」。
 */
public enum ExcelTaskStatus {

    /** 已受理，等待线程池调度 */
    PENDING(0, "待执行"),

    /** 执行中，前端可轮询进度 */
    RUNNING(1, "执行中"),

    /** 执行完毕（导入可能含部分失败行，看 failRows） */
    SUCCESS(2, "已完成"),

    /** 任务级失败：文件无法解析、执行异常等，失败原因见 errorMsg */
    FAILED(3, "失败");

    private final int code;

    private final String label;

    ExcelTaskStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 是否为终态（终态才允许下载结果文件、才允许再次提交同类任务）
     */
    public static boolean isFinished(Integer status) {
        return status != null
                && (status == SUCCESS.code || status == FAILED.code);
    }

    public static ExcelTaskStatus of(Integer status) {
        if (status == null) {
            return PENDING;
        }
        for (ExcelTaskStatus value : values()) {
            if (value.code == status) {
                return value;
            }
        }
        return PENDING;
    }
}
