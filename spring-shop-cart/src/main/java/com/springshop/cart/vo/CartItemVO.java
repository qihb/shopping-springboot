package com.springshop.cart.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * 购物车条目出参
 *
 * <p>{@code invalid} 为失效标记：SKU 已删除 / SKU 停售 / 商品下架时置为 true，
 * 前端据此置灰并禁止结算；失效条目仍保留在列表中，等用户自行删除。
 */
@Schema(description = "购物车条目")
public class CartItemVO {

    @Schema(description = "购物车条目 id")
    private Long id;

    @Schema(description = "SKU id")
    private Long skuId;

    @Schema(description = "商品 id")
    private Long productId;

    @Schema(description = "商品名称")
    private String productName;

    @Schema(description = "商品图片")
    private String productImage;

    @Schema(description = "SKU 销售规格")
    private String specs;

    @Schema(description = "SKU 现价，单位：元")
    private BigDecimal price;

    @Schema(description = "划线价 / 原价，单位：元")
    private BigDecimal originalPrice;

    @Schema(description = "购买数量")
    private Integer quantity;

    @Schema(description = "是否勾选：true-已勾选，false-未勾选")
    private Boolean checked;

    @Schema(description = "当前剩余库存")
    private Integer stock;

    /**
     * 小计金额 = 单价 × 数量
     */
    @Schema(description = "小计金额 = 单价 × 数量，单位：元")
    private BigDecimal subtotal;

    @Schema(description = "是否失效：true-已失效（SKU 已删除 / 停售 / 商品下架），前端据此置灰并禁止结算")
    private Boolean invalid;

    @Schema(description = "失效原因说明")
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
