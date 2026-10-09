package com.springshop.product.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 后台调整库存入参（绝对赋值）
 */
@Schema(description = "后台调整库存入参")
public class InventoryAdjustRequest {

    @Schema(description = "调整后的在库实物量（不能小于当前锁定量）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "在库量不能为空")
    @Min(value = 0, message = "在库量不能为负数")
    private Integer stock;

    @Schema(description = "调整备注")
    private String remark;

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
