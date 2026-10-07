package com.springshop.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 下单入参
 *
 * <p>下单来源固定为购物车勾选项，因此只需收货地址与买家备注。
 */
@Schema(description = "下单入参（下单来源固定为购物车勾选项）")
public class OrderCreateRequest {

    @Schema(description = "收货地址 id")
    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    @Schema(description = "买家备注，不能超过 255 个字符")
    @Size(max = 255, message = "买家备注不能超过 255 个字符")
    private String remark;

    public OrderCreateRequest() {
    }

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
