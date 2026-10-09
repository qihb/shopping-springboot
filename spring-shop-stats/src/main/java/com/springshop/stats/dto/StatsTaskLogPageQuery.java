package com.springshop.stats.dto;

import com.springshop.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 统计任务执行日志分页查询入参
 *
 * <p><b>为什么按「执行时间」而不是「业务日期」过滤</b>：表里同时有
 * {@code stat_date}（业务日期，DATE）与 {@code start_time}（实际执行时刻，DATETIME）。
 * 运营的问题是「这几天任务到底跑没跑、失败没有」—— 那是执行时刻的语义；
 * 而且表上只有 {@code idx_stats_task_log_name_time (task_name, start_time)} 这一个索引，
 * 按 {@code stat_date} 过滤用不上它。手动补数（为过去的 {@code stat_date} 重跑）
 * 恰好也只在「执行时间」上能看见，按业务日期过滤反而会把补数记录藏起来。
 */
@Schema(description = "统计任务执行日志分页查询入参")
public class StatsTaskLogPageQuery extends PageQuery {

    /** 任务名，精确匹配；不传则只查默认任务（cart-recall） */
    @Schema(description = "任务名，精确匹配；不传则只查默认任务（cart-recall）")
    private String taskName;

    /** 执行结果：1 成功 / 0 失败；不传则不过滤 */
    @Schema(description = "执行结果：1 成功 / 0 失败；不传则不过滤")
    private Integer status;

    /** 起始日期（含），格式 yyyy-MM-dd */
    @Schema(description = "起始日期（含），格式 yyyy-MM-dd")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** 结束日期（含），格式 yyyy-MM-dd */
    @Schema(description = "结束日期（含），格式 yyyy-MM-dd")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }
}
