package com.springshop.product.product.vo;

import java.math.BigDecimal;

/**
 * 商品 SKU 出参
 */
public class ProductSkuVO {

    private Long id;

    private String skuCode;

    private String specs;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer stock;

    private Integer status;

    public ProductSkuVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
    public String getSpecs() { return specs; }
    public void setSpecs(String specs) { this.specs = specs; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
