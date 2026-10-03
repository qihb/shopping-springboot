package com.springshop.admin.dto;

import java.time.LocalDateTime;

/**
 * 操作日志导出查询条件
 *
 * <p>字段与列表页筛选条件保持一致，保证「页面上筛出来的」和「导出文件里的」是同一批数据。
 *
 * <p>时间字段用 {@code yyyy-MM-dd HH:mm:ss} 的字符串传（与列表页一致），
 * 由全局 Jackson 配置的 {@code LocalDateTimeDeserializer} 解析，
 * 因此这里**不需要** {@code @DateTimeFormat} —— 那是给表单/查询参数绑定用的，
 * 在 JSON 请求体上不生效（导出走 POST + JSON）。
 */
public class OperationLogExportQuery {

    /** 所属模块，精确匹配 */
    private String module;

    /** 操作管理员用户名，模糊匹配 */
    private String username;

    /** 操作描述，模糊匹配 */
    private String operation;

    /** 执行结果：1 成功 / 0 失败 */
    private Integer status;

    /** 起始时间（含），格式 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime startTime;

    /** 结束时间（含），格式 yyyy-MM-dd HH:mm:ss */
    private LocalDateTime endTime;

    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
}
