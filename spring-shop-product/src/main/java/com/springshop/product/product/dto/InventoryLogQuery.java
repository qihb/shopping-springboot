package com.springshop.product.product.dto;

import com.springshop.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 库存流水查询入参
 *
 * <p>继承 {@link PageQuery}（{@code current} / {@code size}）—— 参数名必须用这一套，
 * 上一轮加的 {@code PageParamGuardInterceptor} 会拒绝 {@code pageNo} / {@code pageSize} 这类错误写法。
 */
@Schema(description = "库存流水查询入参")
public class InventoryLogQuery extends PageQuery {

    @Schema(description = "变更类型：1 下单锁定 / 2 支付出库 / 3 取消释放 / 4 后台调整 / 5 导入初始化")
    private Integer changeType;

    @Schema(description = "关联业务单号（订单号），精确匹配")
    private String bizNo;

    @Schema(description = "起始日期（含），格式 yyyy-MM-dd")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @Schema(description = "结束日期（含），格式 yyyy-MM-dd")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    public Integer getChangeType() { return changeType; }
    public void setChangeType(Integer changeType) { this.changeType = changeType; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
}
