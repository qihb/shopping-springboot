package com.springshop.common.excel.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Excel 异步任务（导入 / 导出共用）
 *
 * <p><b>为什么任务状态要落库而不是放内存/Redis</b>：上万行的导入要跑几十秒到几分钟，
 * 这期间用户会刷新页面、切换菜单、甚至应用会滚动重启。放内存的任务表重启即丢，
 * 放 Redis 也有 TTL 到期与重启丢数据的问题，用户看到的就是「一直转圈」。
 * 落库还能顺带回答「谁在什么时候导了什么」这个审计问题。
 *
 * <p>{@code taskNo} 是对外唯一标识（不是自增 id）：前端轮询与下载都用它，
 * 避免把自增主键暴露出去被遍历。
 */
@TableName("excel_task")
public class ExcelTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务号，对外唯一标识 */
    private String taskNo;

    /** 业务类型编码，如 PRODUCT_IMPORT */
    private String bizType;

    /** 业务类型展示名，如「商品导入」 */
    private String bizName;

    /** 任务方向：1 导入 / 2 导出 */
    private Integer taskType;

    /** 任务状态：0 待执行 / 1 执行中 / 2 成功 / 3 失败 */
    private Integer status;

    /** 文件名：导入为上传的原始名，导出为生成的文件名 */
    private String fileName;

    /** 临时文件绝对路径（导入源文件 / 导出结果文件） */
    private String filePath;

    /** 导出查询条件 JSON */
    private String params;

    /** 总行数 */
    private Integer totalRows;

    /** 已处理行数 */
    private Integer processedRows;

    /** 成功行数 */
    private Integer successRows;

    /** 失败行数 */
    private Integer failRows;

    /** 任务级失败原因 */
    private String errorMsg;

    /** 提交人（管理员 id） */
    private Long createdBy;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public ExcelTask() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getBizName() { return bizName; }
    public void setBizName(String bizName) { this.bizName = bizName; }
    public Integer getTaskType() { return taskType; }
    public void setTaskType(Integer taskType) { this.taskType = taskType; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }
    public Integer getProcessedRows() { return processedRows; }
    public void setProcessedRows(Integer processedRows) { this.processedRows = processedRows; }
    public Integer getSuccessRows() { return successRows; }
    public void setSuccessRows(Integer successRows) { this.successRows = successRows; }
    public Integer getFailRows() { return failRows; }
    public void setFailRows(Integer failRows) { this.failRows = failRows; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
