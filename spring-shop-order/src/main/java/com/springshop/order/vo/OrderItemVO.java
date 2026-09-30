package com.springshop.order.vo;

import java.math.BigDecimal;

/**
 * 订单明细出参（下单时的商品快照）
 */
public class OrderItemVO {

    private Long productId;

    private Long skuId;

    private String productName;

    /** SKU 规格描述快照 */
    private String skuSpecs;

    private String productImage;

    /** 成交单价快照 */
    private BigDecimal price;

    private Integer quantity;

    /** 小计 = price × quantity */
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
