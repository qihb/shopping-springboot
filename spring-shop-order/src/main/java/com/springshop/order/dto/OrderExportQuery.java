package com.springshop.order.dto;

import java.util.List;

/**
 * 订单导出查询条件
 *
 * <p>字段与后台订单列表的筛选条件保持一致，保证「页面上筛出来的」和「导出文件里的」
 * 是同一批数据。
 *
 * <p>不继承 {@code PageQuery}：导出没有分页概念，分页由后台线程按
 * {@code excel.task.export-page-size} 自己推进。
 */
public class OrderExportQuery {

    /** 订单号，模糊匹配 */
    private String orderNo;

    /** 订单状态，为空表示不限 */
    private Integer status;

    /** 指定订单 id 列表（「导出选中」）；为空表示按筛选条件导出全部 */
    private List<Long> ids;

    public OrderExportQuery() {
    }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }
}
