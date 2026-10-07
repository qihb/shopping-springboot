package com.springshop.order.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 订单明细出参（下单时的商品快照）
 */
@Schema(description = "订单明细出参（下单时的商品快照）")
public class OrderItemVO {

    @Schema(description = "商品 id")
    private Long productId;

    @Schema(description = "SKU id")
    private Long skuId;

    @Schema(description = "商品名称快照")
    private String productName;

    /** SKU 规格描述快照 */
    @Schema(description = "SKU 规格描述快照")
    private String skuSpecs;

    @Schema(description = "商品主图快照")
    private String productImage;

    /** 成交单价快照 */
    @Schema(description = "成交单价快照（元）")
    private BigDecimal price;

    @Schema(description = "购买数量")
    private Integer quantity;

    /** 小计 = price × quantity */
    @Schema(description = "小计金额（元）= 成交单价 × 购买数量")
    private BigDecimal subtotal;

    public OrderItemVO() {
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getSkuSpecs() { return skuSpecs; }
    public void setSkuSpecs(String skuSpecs) { this.skuSpecs = skuSpecs; }
    public String getProductImage() { return productImage; }
    public void setProductImage(String productImage) { this.productImage = productImage; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
}
