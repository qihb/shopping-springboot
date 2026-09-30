package com.springshop.cart.vo;

import java.math.BigDecimal;

/**
 * 购物车条目出参
 *
 * <p>{@code invalid} 为失效标记：SKU 已删除 / SKU 停售 / 商品下架时置为 true，
 * 前端据此置灰并禁止结算；失效条目仍保留在列表中，等用户自行删除。
 */
public class CartItemVO {

    private Long id;

    private Long skuId;

    private Long productId;

    private String productName;

    private String productImage;

    private String specs;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer quantity;

    private Boolean checked;

    private Integer stock;

    /**
     * 小计金额 = 单价 × 数量
     */
    private BigDecimal subtotal;

    private Boolean invalid;

    private String invalidReason;

    public CartItemVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getProductImage() { return productImage; }
    public void setProductImage(String productImage) { this.productImage = productImage; }
    public String getSpecs() { return specs; }
    public void setSpecs(String specs) { this.specs = specs; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Boolean getChecked() { return checked; }
    public void setChecked(Boolean checked) { this.checked = checked; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public Boolean getInvalid() { return invalid; }
    public void setInvalid(Boolean invalid) { this.invalid = invalid; }
    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String invalidReason) { this.invalidReason = invalidReason; }
}
