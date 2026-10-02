package com.springshop.pay.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付结果 VO
 */
public class PayResultVO {

    private String orderNo;

    private BigDecimal amount;

    private Integer status;

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
