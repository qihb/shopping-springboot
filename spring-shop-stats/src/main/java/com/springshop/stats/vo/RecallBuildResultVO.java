package com.springshop.stats.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * 圈人任务执行结果
 */
@Schema(description = "圈人任务执行结果")
public class RecallBuildResultVO {

    @Schema(description = "统计日期")
    private LocalDate statDate;

    /** 入池条目数 */
    @Schema(description = "入池条目数")
    private Integer targetCount;

    /** 入池用户数（去重） */
    @Schema(description = "入池用户数（去重）")
    private Integer userCount;

    /** 其中可触达条目数 */
    @Schema(description = "其中可触达条目数")
    private Integer reachableCount;

    /** 选品输出条数 */
    @Schema(description = "选品输出条数")
    private Integer productCount;

    /** 被剔除的异常账号数 */
    @Schema(description = "被剔除的异常账号数")
    private Integer excludedUserCount;

    @Schema(description = "执行耗时（毫秒）")
    private Long durationMs;

    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Integer getTargetCount() { return targetCount; }
    public void setTargetCount(Integer targetCount) { this.targetCount = targetCount; }
    public Integer getUserCount() { return userCount; }
    public void setUserCount(Integer userCount) { this.userCount = userCount; }
    public Integer getReachableCount() { return reachableCount; }
    public void setReachableCount(Integer reachableCount) { this.reachableCount = reachableCount; }
    public Integer getProductCount() { return productCount; }
    public void setProductCount(Integer productCount) { this.productCount = productCount; }
    public Integer getExcludedUserCount() { return excludedUserCount; }
    public void setExcludedUserCount(Integer excludedUserCount) { this.excludedUserCount = excludedUserCount; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}
