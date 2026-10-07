package com.springshop.order.dto;

import com.springshop.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后台订单分页查询入参
 */
@Schema(description = "后台订单分页查询入参")
public class AdminOrderPageQuery extends PageQuery {

    /** 订单号（模糊匹配），为空则不筛选 */
    @Schema(description = "订单号，模糊匹配；为空则不筛选")
    private String orderNo;

    /** 订单状态筛选，为空则不筛选 */
    @Schema(description = "订单状态筛选：1-待付款，2-待发货，3-待收货，4-已完成，5-已取消，6-已退款；为空则不筛选")
    private Integer status;

    public AdminOrderPageQuery() {
    }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
