package com.springshop.product.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 商品 SKU 保存项
 */
@Schema(description = "商品 SKU 保存项")
public class ProductSkuItem {

    @Schema(description = "SKU id")
    private Long id;

    @Schema(description = "SKU 编码")
    @NotBlank(message = "SKU 编码不能为空")
    private String skuCode;

    @Schema(description = "规格描述，长度不超过 255")
    @Size(max = 255, message = "规格描述长度不超过 255")
    private String specs;

    @Schema(description = "销售价（元）")
    @NotNull(message = "销售价不能为空")
    private BigDecimal price;

    @Schema(description = "划线价/原价（元）")
    private BigDecimal originalPrice;

    @Schema(description = "库存数量")
    private Integer stock;

    @Schema(description = "状态：1-启用，0-停用")
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
