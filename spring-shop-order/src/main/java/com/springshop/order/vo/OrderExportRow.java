package com.springshop.order.vo;

import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 订单导出模型
 *
 * <p>一行一个订单，商品明细压缩到一列（「商品名 x 数量」用分号拼接）：
 * 一个订单可能有几十条明细，若展开成多行会让「订单金额」这类订单级字段
 * 在同一订单内重复出现，做数据透视时极易重复求和。
 *
 * <p>时间统一格式化成字符串：导出是给人看的，不希望同一列在不同机器上
 * 因为时区/格式配置差异而变样。
 */
public class OrderExportRow {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单列文本上限，防止恶意超长备注把单元格撑爆（Excel 单元格上限 32767 字符） */
    private static final int MAX_TEXT_LENGTH = 1000;

    @ExcelProperty("订单ID")
    private Long id;

    @ExcelProperty("订单号")
    private String orderNo;

    @ExcelProperty("订单状态")
    private String statusDesc;

    @ExcelProperty("订单金额(元)")
    private BigDecimal totalAmount;

    @ExcelProperty("实付金额(元)")
    private BigDecimal payAmount;

    @ExcelProperty("收货人")
    private String receiverName;

    @ExcelProperty("收货电话")
    private String receiverPhone;

    @ExcelProperty("收货地址")
    private String receiverAddress;

    @ExcelProperty("商品明细")
    private String itemsSummary;

    @ExcelProperty("备注")
    private String remark;

    @ExcelProperty("下单时间")
    private String createTime;

    @ExcelProperty("支付时间")
    private String payTime;

    @ExcelProperty("发货时间")
    private String shipTime;

    @ExcelProperty("完成时间")
    private String finishTime;

    @ExcelProperty("取消时间")
    private String cancelTime;

    public OrderExportRow() {
    }

    /**
     * 从列表 VO 转换（导出直接复用列表页的查询结果，保证「看到的」和「导出的」一致）
     */
    public static OrderExportRow from(OrderVO vo) {
        OrderExportRow row = new OrderExportRow();
        row.setId(vo.getId());
        row.setOrderNo(vo.getOrderNo());
        row.setStatusDesc(vo.getStatusDesc());
        row.setTotalAmount(vo.getTotalAmount());
        row.setPayAmount(vo.getPayAmount());
        row.setReceiverName(vo.getReceiverName());
        row.setReceiverPhone(vo.getReceiverPhone());
        row.setReceiverAddress(vo.getReceiverAddress());
        row.setItemsSummary(summarizeItems(vo.getItems()));
        row.setRemark(truncate(vo.getRemark()));
        row.setCreateTime(formatTime(vo.getCreateTime()));
        row.setPayTime(formatTime(vo.getPayTime()));
        row.setShipTime(formatTime(vo.getShipTime()));
        row.setFinishTime(formatTime(vo.getFinishTime()));
        row.setCancelTime(formatTime(vo.getCancelTime()));
        return row;
    }

    private static String summarizeItems(List<OrderItemVO> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (OrderItemVO item : items) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(item.getProductName() == null ? "" : item.getProductName())
                    .append(" x ")
                    .append(item.getQuantity() == null ? 0 : item.getQuantity());
        }
        return truncate(sb.toString());
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FORMAT);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getStatusDesc() { return statusDesc; }
    public void setStatusDesc(String statusDesc) { this.statusDesc = statusDesc; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(BigDecimal payAmount) { this.payAmount = payAmount; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverPhone() { return receiverPhone; }
    public void setReceiverPhone(String receiverPhone) { this.receiverPhone = receiverPhone; }
    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }
    public String getItemsSummary() { return itemsSummary; }
    public void setItemsSummary(String itemsSummary) { this.itemsSummary = itemsSummary; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getPayTime() { return payTime; }
    public void setPayTime(String payTime) { this.payTime = payTime; }
    public String getShipTime() { return shipTime; }
    public void setShipTime(String shipTime) { this.shipTime = shipTime; }
    public String getFinishTime() { return finishTime; }
    public void setFinishTime(String finishTime) { this.finishTime = finishTime; }
    public String getCancelTime() { return cancelTime; }
    public void setCancelTime(String cancelTime) { this.cancelTime = cancelTime; }
}
