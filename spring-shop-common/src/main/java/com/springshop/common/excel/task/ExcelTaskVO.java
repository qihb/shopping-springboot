package com.springshop.common.excel.task;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Excel 任务 VO
 *
 * <p>除了任务表字段，额外给出两个「前端不用自己算」的派生字段：
 * {@code progress}（百分比进度）与 {@code downloadable}（能否下载结果）。
 * 前端轮询只做展示，不需要复制一套状态判断逻辑。
 */
@Schema(description = "异步导入导出任务")
public class ExcelTaskVO {

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "业务类型编码")
    private String bizType;

    @Schema(description = "业务类型名称")
    private String bizName;

    @Schema(description = "任务方向：1 导入 / 2 导出")
    private Integer taskType;

    @Schema(description = "任务方向名称")
    private String taskTypeName;

    @Schema(description = "任务状态：0 待执行 / 1 执行中 / 2 成功 / 3 失败")
    private Integer status;

    @Schema(description = "任务状态名称")
    private String statusName;

    @Schema(description = "结果文件名")
    private String fileName;

    @Schema(description = "总行数")
    private Integer totalRows;

    @Schema(description = "已处理行数")
    private Integer processedRows;

    @Schema(description = "成功行数")
    private Integer successRows;

    @Schema(description = "失败行数")
    private Integer failRows;

    /** 进度百分比（0~100）；导出任务没有可预期的总量，完成前恒为 0 */
    @Schema(description = "进度百分比（0~100）；导出任务完成前恒为 0")
    private Integer progress;

    /** 是否可下载：导出任务需成功且有文件；导入任务需有失败明细 */
    @Schema(description = "是否可下载：导出任务需成功且有文件；导入任务需有失败明细")
    private boolean downloadable;

    @Schema(description = "失败原因，成功时为 null")
    private String errorMsg;

    /**
     * 实际落库的失败明细条数
     *
     * <p>失败明细有上限（{@code excel.task.max-error-rows}），超过后只累加
     * {@code failRows} 而不再落库。所以「失败行数」与「能下载到的明细条数」
     * 可能不相等——前端要能同时看到这两个数。
     *
     * <p><b>只有任务详情接口会填这两个字段</b>，任务列表返回 {@code null}
     * （含义是「未计算」，而不是「0 条」）：列表里逐条任务都去 count 一次明细
     * 就是 N 次查询，而列表页并不需要这个数。
     */
    @Schema(description = "实际记录下来的失败明细条数；任务列表不返回（null）")
    private Integer detailRows;

    /**
     * 未记录的失败明细条数 = {@code failRows - detailRows}
     *
     * <p>大于 0 就表示失败明细被截断了：下载到的明细文件是不完整的，
     * 不能拿它当「全部失败原因」来对账。没有这个字段时，用户只能看到
     * 「失败 12345 行」和一个只有 10000 行的文件，且没有任何提示。
     */
    @Schema(description = "未记录下来的失败明细条数；大于 0 表示明细被截断；任务列表不返回（null）")
    private Integer unrecordedErrorRows;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public ExcelTaskVO() {
    }

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizName() { return bizName; }
    public void setBizName(String bizName) { this.bizName = bizName; }
    public Integer getTaskType() { return taskType; }
    public void setTaskType(Integer taskType) { this.taskType = taskType; }
    public String getTaskTypeName() { return taskTypeName; }
    public void setTaskTypeName(String taskTypeName) { this.taskTypeName = taskTypeName; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getStatusName() { return statusName; }
    public void setStatusName(String statusName) { this.statusName = statusName; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }
    public Integer getProcessedRows() { return processedRows; }
    public void setProcessedRows(Integer processedRows) { this.processedRows = processedRows; }
    public Integer getSuccessRows() { return successRows; }
    public void setSuccessRows(Integer successRows) { this.successRows = successRows; }
    public Integer getFailRows() { return failRows; }
    public void setFailRows(Integer failRows) { this.failRows = failRows; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public boolean isDownloadable() { return downloadable; }
    public void setDownloadable(boolean downloadable) { this.downloadable = downloadable; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Integer getDetailRows() { return detailRows; }
    public void setDetailRows(Integer detailRows) { this.detailRows = detailRows; }
    public Integer getUnrecordedErrorRows() { return unrecordedErrorRows; }
    public void setUnrecordedErrorRows(Integer unrecordedErrorRows) { this.unrecordedErrorRows = unrecordedErrorRows; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
