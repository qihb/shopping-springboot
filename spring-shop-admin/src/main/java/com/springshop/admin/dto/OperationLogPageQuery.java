package com.springshop.admin.dto;

import com.springshop.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 操作日志分页查询入参
 */
@Schema(description = "操作日志分页查询入参")
public class OperationLogPageQuery extends PageQuery {

    /** 所属模块，精确匹配（如 系统管理 / 商品管理） */
    @Schema(description = "所属模块，精确匹配（如 系统管理 / 商品管理）")
    private String module;

    /** 操作管理员用户名，模糊匹配 */
    @Schema(description = "操作管理员用户名，模糊匹配")
    private String username;

    /** 操作描述，模糊匹配 */
    @Schema(description = "操作描述，模糊匹配")
    private String operation;

    /** 执行结果：1 成功 / 0 失败 */
    @Schema(description = "执行结果：1 成功 / 0 失败")
    private Integer status;

    /** 起始时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @Schema(description = "起始时间（含），格式 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /** 结束时间（含），格式 yyyy-MM-dd HH:mm:ss */
    @Schema(description = "结束时间（含），格式 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }
}
