package com.springshop.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 操作日志（审计记录）
 */
@Schema(description = "操作日志（审计记录）")
public class OperationLogVO {

    @Schema(description = "日志 id")
    private Long id;

    /** 操作管理员 id */
    @Schema(description = "操作管理员 id")
    private Long adminUserId;

    /** 操作管理员用户名 */
    @Schema(description = "操作管理员用户名")
    private String username;

    /** 所属模块，如 商品管理 */
    @Schema(description = "所属模块，如 商品管理")
    private String module;

    /** 操作描述，如 新增商品 */
    @Schema(description = "操作描述，如 新增商品")
    private String operation;

    /** 请求路径 */
    @Schema(description = "请求路径")
    private String requestUri;

    /** 请求方式 GET/POST/PUT/DELETE */
    @Schema(description = "请求方式 GET/POST/PUT/DELETE")
    private String requestMethod;

    /** 请求参数（已脱敏） */
    @Schema(description = "请求参数（已脱敏）")
    private String requestParams;

    /** 操作人 IP */
    @Schema(description = "操作人 IP")
    private String ip;

    /** 执行结果：1 成功 / 0 失败 */
    @Schema(description = "执行结果：1 成功 / 0 失败")
    private Integer status;

    /** 异常信息（失败时记录） */
    @Schema(description = "异常信息（失败时记录）")
    private String errorMsg;

    /** 耗时（毫秒） */
    @Schema(description = "耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAdminUserId() {
        return adminUserId;
    }

    public void setAdminUserId(Long adminUserId) {
        this.adminUserId = adminUserId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public String getRequestParams() {
        return requestParams;
    }

    public void setRequestParams(String requestParams) {
        this.requestParams = requestParams;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
