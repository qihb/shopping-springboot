package com.springshop.stats.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统计任务执行日志出参
 *
 * <p>为什么不直接返回 {@code StatsTaskLog} 实体：AGENTS.md 的红线是「Entity 不直接返回给前端」。
 * 实体的字段集将来会随存储变化（比如加内部标记列），而对外契约不该跟着抖。
 *
 * <p>字段与实体一一对应，没有做裁剪 —— 排查失败任务时 {@code errorMsg} 与
 * {@code durationMs} 都是核心信息，少任何一个这个接口就没有存在意义。
 */
@Schema(description = "统计任务执行日志")
public class StatsTaskLogVO {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "任务名，如 cart-recall")
    private String taskName;

    @Schema(description = "业务日期（该次执行针对哪一天的数据）")
    private LocalDate statDate;

    @Schema(description = "执行结果：1 成功 / 0 失败")
    private Integer status;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "产出/处理行数")
    private Integer rowCount;

    @Schema(description = "失败原因；成功时为 null")
    private String errorMsg;

    @Schema(description = "记录写入时间")
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
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

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
