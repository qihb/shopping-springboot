package com.springshop.common.excel.task;

import java.time.LocalDateTime;

/**
 * Excel 任务 VO
 *
 * <p>除了任务表字段，额外给出两个「前端不用自己算」的派生字段：
 * {@code progress}（百分比进度）与 {@code downloadable}（能否下载结果）。
 * 前端轮询只做展示，不需要复制一套状态判断逻辑。
 */
public class ExcelTaskVO {

    private String taskNo;

    private String bizType;

    private String bizName;

    private Integer taskType;

    private String taskTypeName;

    private Integer status;

    private String statusName;

    private String fileName;

    private Integer totalRows;

    private Integer processedRows;

    private Integer successRows;

    private Integer failRows;

    /** 进度百分比（0~100）；导出任务没有可预期的总量，完成前恒为 0 */
    private Integer progress;

    /** 是否可下载：导出任务需成功且有文件；导入任务需有失败明细 */
    private boolean downloadable;

    private String errorMsg;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

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
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
