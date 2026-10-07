package com.springshop.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 修改购物车条目数量入参
 */
@Schema(description = "修改购物车条目数量请求")
public class CartQuantityRequest {

    @Schema(description = "新的购买数量，至少为 1")
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量至少为 1")
    private Integer quantity;

    public CartQuantityRequest() {
    }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
}
