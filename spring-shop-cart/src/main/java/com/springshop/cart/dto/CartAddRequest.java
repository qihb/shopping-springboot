package com.springshop.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 加购入参
 */
@Schema(description = "加入购物车请求")
public class CartAddRequest {

    @Schema(description = "要加入购物车的 SKU id")
    @NotNull(message = "SKU 不能为空")
    private Long skuId;

    @Schema(description = "购买数量，至少为 1")
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量至少为 1")
    private Integer quantity;

    public CartAddRequest() {
    }

    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
