package com.springshop.stats.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 待召回候选条目（聚合查询的中间结果）
 *
 * <p>为什么不直接复用 {@code CartRecallTarget} 实体：键集分页需要携带 {@code cart_item.id}
 * 作为游标，而该列并不存在于 {@code cart_recall_target} 表中。把查询专用字段塞进实体会让
 * 实体与表结构不再一一对应，因此单独用一个 VO 承载查询结果。
 */
@Schema(description = "待召回候选条目（聚合查询的中间结果）")
public class RecallCandidateVO {

    /** cart_item 主键，仅用作键集分页游标 */
    @Schema(description = "cart_item 主键，仅用作键集分页游标")
    private Long cartItemId;

    @Schema(description = "用户 id")
    private Long userId;

    @Schema(description = "SKU id")
    private Long skuId;

    @Schema(description = "商品 id")
    private Long productId;

    @Schema(description = "商品名称")
    private String productName;

    @Schema(description = "商品主图 URL")
    private String mainImage;

    @Schema(description = "SKU 现价（元）")
    private BigDecimal skuPrice;

    @Schema(description = "加购数量")
    private Integer quantity;

    @Schema(description = "加购金额（元）= 现价 × 数量")
    private BigDecimal cartAmount;

    @Schema(description = "首次加购时间（取自 cart_item.create_time）")
    private LocalDateTime addTime;

    @Schema(description = "用户手机号（可空）")
    private String userPhone;

    @Schema(description = "用户小程序 openid（可空）")
    private String userOpenid;

    public Long getCartItemId() { return cartItemId; }
    public void setCartItemId(Long cartItemId) { this.cartItemId = cartItemId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public BigDecimal getSkuPrice() { return skuPrice; }
    public void setSkuPrice(BigDecimal skuPrice) { this.skuPrice = skuPrice; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getCartAmount() { return cartAmount; }
    public void setCartAmount(BigDecimal cartAmount) { this.cartAmount = cartAmount; }
    public LocalDateTime getAddTime() { return addTime; }
    public void setAddTime(LocalDateTime addTime) { this.addTime = addTime; }
    public String getUserPhone() { return userPhone; }
    public void setUserPhone(String userPhone) { this.userPhone = userPhone; }
    public String getUserOpenid() { return userOpenid; }
    public void setUserOpenid(String userOpenid) { this.userOpenid = userOpenid; }
}
