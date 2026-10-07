package com.springshop.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单出参（列表与详情共用）
 *
 * <p>收货信息为下单时快照，不随地址变更而漂移。
 */
@Schema(description = "订单出参（列表与详情共用），收货信息与金额均为下单时快照")
public class OrderVO {

    @Schema(description = "订单 id")
    private Long id;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "订单总金额（元）")
    private BigDecimal totalAmount;

    @Schema(description = "实付金额（元）")
    private BigDecimal payAmount;

    @Schema(description = "订单状态：1-待付款，2-待发货，3-待收货，4-已完成，5-已取消，6-已退款")
    private Integer status;

    /** 状态描述，由 OrderStatus.descOf(status) 转换 */
    @Schema(description = "订单状态描述")
    private String statusDesc;

    @Schema(description = "收货人姓名（下单时快照）")
    private String receiverName;

    @Schema(description = "收货人手机号（下单时快照）")
    private String receiverPhone;

    @Schema(description = "收货地址（下单时快照，省市区与详细地址拼接）")
    private String receiverAddress;

    @Schema(description = "买家备注")
    private String remark;

    @Schema(description = "下单时间")
    private LocalDateTime createTime;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    @Schema(description = "发货时间")
    private LocalDateTime shipTime;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    @Schema(description = "取消时间")
    private LocalDateTime cancelTime;

    /** 订单明细快照 */
    @Schema(description = "订单明细快照")
    private List<OrderItemVO> items;

    public OrderVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getStatusDesc() { return statusDesc; }
    public void setStatusDesc(String statusDesc) { this.statusDesc = statusDesc; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverPhone() { return receiverPhone; }
    public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getPayTime() { return payTime; }
    public void setPayTime(LocalDateTime payTime) { this.payTime = payTime; }
    public LocalDateTime getShipTime() { return shipTime; }
    public void setShipTime(LocalDateTime shipTime) { this.shipTime = shipTime; }
    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }
    public LocalDateTime getCancelTime() { return cancelTime; }
    public void setCancelTime(LocalDateTime cancelTime) { this.cancelTime = cancelTime; }
    public List<OrderItemVO> getItems() { return items; }
    public void setItems(List<OrderItemVO> items) { this.items = items; }
}
