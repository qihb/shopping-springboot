package com.springshop.pay.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付结果 VO
 */
@Schema(description = "支付结果")
public class PayResultVO {

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "支付金额，单位：元")
    private BigDecimal amount;

    @Schema(description = "支付状态：1-支付成功（模拟网关一次到位）")
    private Integer status;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getPayTime() { return payTime; }
    public void setPayTime(LocalDateTime payTime) { this.payTime = payTime; }
}
