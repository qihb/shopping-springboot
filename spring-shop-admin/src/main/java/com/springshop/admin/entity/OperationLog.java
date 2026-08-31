package com.springshop.admin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 操作日志实体，对应表 operation_log
 *
 * <p>记录管理员的敏感操作（谁、何时、做了什么、结果如何），供审计追溯。
 */
@TableName("operation_log")
public class OperationLog {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作管理员 id */
    private Long adminUserId;

    /** 操作管理员用户名 */
    private String username;

    /** 所属模块，如 商品管理 */
    private String module;

    /** 操作描述，如 新增商品 */
    private String operation;

    /** 请求路径 */
    private String requestUri;

    /** 请求方式 GET/POST/PUT/DELETE */
    private String requestMethod;

    /** 请求参数（脱敏后记录） */
    private String requestParams;

    /** 操作人 IP */
    private String ip;

    /** 执行结果：1 成功 / 0 失败 */
    private Integer status;

    /** 异常信息（失败时记录） */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 创建时间（插入时自动填充） */
    @TableField(fill = FieldFill.INSERT)
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
