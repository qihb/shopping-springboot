package com.springshop.common.excel.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Excel 导入失败明细
 *
 * <p>单独建表而不是把明细序列化成 JSON 塞在 {@link ExcelTask} 的一个列里：
 * 一个列填错的文件可能 1 万行全失败，JSON 列会膨胀到几百 KB，
 * 且无法按行号排序、分页或只取前 N 条。
 */
@TableName("excel_task_error")
public class ExcelTaskError {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;

    /** 出错行号，与 Excel 界面显示的行号一致（表头为第 1 行） */
    private Integer rowNum;

    private String message;

    private LocalDateTime createTime;

    public ExcelTaskError() {
    }

    public ExcelTaskError(String taskNo, Integer rowNum, String message) {
        this.taskNo = taskNo;
        this.rowNum = rowNum;
        this.message = message;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public Integer getRowNum() { return rowNum; }
    public void setRowNum(Integer rowNum) { this.rowNum = rowNum; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
