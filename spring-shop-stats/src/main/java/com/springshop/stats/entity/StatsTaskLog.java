package com.springshop.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统计任务执行日志
 *
 * <p>{@code @Scheduled} 方法抛出的异常不会杀掉调度线程（下次 cron 照常触发），
 * 但会被框架吞掉、只留一行日志，很容易出现「今天没出数据但没人发现」。
 * 这张表是排查「今天为什么没出数据」的唯一入口，同时承载耗时与产出行数的监控。
 */
@TableName("stats_task_log")
public class StatsTaskLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskName;

    private LocalDate statDate;

    /** 执行结果：1 成功 / 0 失败 */
    private Integer status;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Integer rowCount;

    private String errorMsg;

    private LocalDateTime createTime;

    public StatsTaskLog() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
