package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 库存视图对象
 *
 * <p>对外只暴露「在库 / 锁定 / 可售」三个量，其中 {@code available = stock - lockedStock}
 * 是派生值，不落库。
 */
@Schema(description = "库存视图")
public class InventoryVO {

    @Schema(description = "SKU id")
    private Long skuId;

    @Schema(description = "在库实物量")
    private Integer stock;

    @Schema(description = "未付款订单锁定中")
    private Integer lockedStock;

    @Schema(description = "可售量 = 在库 - 锁定")
    private Integer available;

    public InventoryVO() {
    }

    public InventoryVO(Long skuId, Integer stock, Integer lockedStock) {
        this.skuId = skuId;
        this.stock = stock;
        this.lockedStock = lockedStock;
        this.available = (stock == null ? 0 : stock) - (lockedStock == null ? 0 : lockedStock);
    }

    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public Integer getLockedStock() { return lockedStock; }
    public void setLockedStock(Integer lockedStock) { this.lockedStock = lockedStock; }
    public Integer getAvailable() { return available; }
    public void setAvailable(Integer available) { this.available = available; }
}
