package com.springshop.common.excel.task;

import com.springshop.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Excel 任务分页查询入参
 *
 * <p>只暴露「筛选自己的任务」所需的最小条件；{@code createdBy} 由服务端从登录态取，
 * 不接受前端传入，避免越权查看他人任务。
 */
@Schema(description = "Excel 任务分页查询入参")
public class ExcelTaskPageQuery extends PageQuery {

    /** 任务方向：1 导入 / 2 导出；为空表示不限 */
    @Schema(description = "任务方向：1 导入 / 2 导出；为空表示不限")
    private Integer taskType;

    /** 业务类型编码；为空表示不限 */
    @Schema(description = "业务类型编码；为空表示不限")
    private String bizType;

    /** 任务状态：0 待执行 / 1 执行中 / 2 成功 / 3 失败；为空表示不限 */
    @Schema(description = "任务状态：0 待执行 / 1 执行中 / 2 成功 / 3 失败；为空表示不限")
    private Integer status;

    public ExcelTaskPageQuery() {
    }

    public Integer getTaskType() { return taskType; }
    public void setTaskType(Integer taskType) { this.taskType = taskType; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
