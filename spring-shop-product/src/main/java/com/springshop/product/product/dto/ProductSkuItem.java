package com.springshop.product.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 商品 SKU 保存项
 */
public class ProductSkuItem {

    private Long id;

    @NotBlank(message = "SKU 编码不能为空")
    private String skuCode;

    @Size(max = 255, message = "规格描述长度不超过 255")
    private String specs;

    @NotNull(message = "销售价不能为空")
    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer stock;

    private Integer status;

    public ProductSkuItem() {
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
