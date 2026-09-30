package com.springshop.order.enums;

/**
 * 订单状态（与 orders.status 字段一一对应）
 *
 * <p>状态流转：待付款 → 待发货 → 待收货 → 已完成；待付款可取消为已取消。
 */
public enum OrderStatus {

    PENDING_PAYMENT(1, "待付款"),
    PENDING_SHIPMENT(2, "待发货"),
    PENDING_RECEIPT(3, "待收货"),
    FINISHED(4, "已完成"),
    CANCELLED(5, "已取消"),
    REFUNDED(6, "已退款");

    private final Integer code;
    private final String desc;

    OrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按状态码取描述，未匹配返回 null（用于 VO 展示，不抛异常）
     */
    public static String descOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status.desc;
            }
        }
        return null;
    }
}
