package com.springshop.admin.vo;

import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 操作日志导出模型
 */
public class OperationLogExportRow {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单元格文本上限：请求参数可能很长，超长会让 xlsx 单元格膨胀且没人看 */
    private static final int MAX_TEXT_LEN = 1000;

    @ExcelProperty("日志ID")
    private Long id;

    @ExcelProperty("操作人")
    private String username;

    @ExcelProperty("模块")
    private String module;

    @ExcelProperty("操作")
    private String operation;

    @ExcelProperty("请求方式")
    private String requestMethod;

    @ExcelProperty("请求路径")
    private String requestUri;

    @ExcelProperty("请求参数")
    private String requestParams;

    @ExcelProperty("IP")
    private String ip;

    @ExcelProperty("结果")
    private String statusName;

    @ExcelProperty("耗时(ms)")
    private Long durationMs;

    @ExcelProperty("失败原因")
    private String errorMsg;

    @ExcelProperty("操作时间")
    private String createTime;

    public OperationLogExportRow() {
    }

    public static OperationLogExportRow from(OperationLogVO vo) {
        OperationLogExportRow row = new OperationLogExportRow();
        row.setId(vo.getId());
        row.setUsername(vo.getUsername());
        row.setModule(vo.getModule());
        row.setOperation(vo.getOperation());
        row.setRequestMethod(vo.getRequestMethod());
        row.setRequestUri(vo.getRequestUri());
        row.setRequestParams(truncate(vo.getRequestParams()));
        row.setIp(vo.getIp());
        row.setStatusName(statusLabel(vo.getStatus()));
        row.setDurationMs(vo.getDurationMs());
        row.setErrorMsg(truncate(vo.getErrorMsg()));
        row.setCreateTime(formatTime(vo.getCreateTime()));
        return row;
    }

    private static String statusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return status == 1 ? "成功" : "失败";
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_TEXT_LEN ? text.substring(0, MAX_TEXT_LEN) + "…" : text;
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FORMAT);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
    public String getRequestUri() { return requestUri; }
    public void setRequestUri(String requestUri) { this.requestUri = requestUri; }
    public String getRequestParams() { return requestParams; }
    public void setRequestParams(String requestParams) { this.requestParams = requestParams; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getStatusName() { return statusName; }
    public void setStatusName(String statusName) { this.statusName = statusName; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
