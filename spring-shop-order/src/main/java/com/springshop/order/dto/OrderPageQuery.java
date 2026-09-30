package com.springshop.order.dto;

import com.springshop.common.dto.PageQuery;

/**
 * 我的订单分页查询入参
 */
public class OrderPageQuery extends PageQuery {

    /** 订单状态筛选，为空则不筛选 */
    private Integer status;

    public OrderPageQuery() {
    }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
