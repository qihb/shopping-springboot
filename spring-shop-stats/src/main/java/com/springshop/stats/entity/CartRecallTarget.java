package com.springshop.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 加购未买待召回人群条目
 *
 * <p>「当前待召回池」的明细行，每天跑批滚动刷新：一条 = 一个用户的一件加购未买商品。
 * 唯一键 {@code uk_recall_user_sku(user_id, sku_id)} 兜底同一用户同一 SKU 不会重复入池。
 *
 * <p>为什么不映射 {@code is_deleted} / {@code version}：池子是可重建的派生数据，
 * 刷新采用「删除 + 重建」，不承载业务上的删除语义，也不需要乐观锁。
 */
@TableName("cart_recall_target")
public class CartRecallTarget {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long skuId;

    private Long productId;

    private String productName;

    private String mainImage;

    private BigDecimal skuPrice;

    private Integer quantity;

    /** 加购金额（元）= 现价 × 数量 */
    private BigDecimal cartAmount;

    /** 首次加购时间（取自 cart_item.create_time） */
    private LocalDateTime addTime;

    /** 已闲置小时数（跑批时刻 - 加购时间） */
    private Integer idleHours;

    private String userPhone;

    private String userOpenid;

    /** 是否可触达：1 有手机号或 openid / 0 均无 */
    private Integer reachable;

    private BigDecimal suggestedAmount;

    /** 处理状态：0 待处理 / 1 已发券 / 2 已转化 / 3 已失效 */
    private Integer status;

    private LocalDate statDate;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public CartRecallTarget() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public Integer getIdleHours() { return idleHours; }
    public void setIdleHours(Integer idleHours) { this.idleHours = idleHours; }
    public String getUserPhone() { return userPhone; }
    public void setUserPhone(String userPhone) { this.userPhone = userPhone; }
    public String getUserOpenid() { return userOpenid; }
    public void setUserOpenid(String userOpenid) { this.userOpenid = userOpenid; }
    public Integer getReachable() { return reachable; }
    public void setReachable(Integer reachable) { this.reachable = reachable; }
    public BigDecimal getSuggestedAmount() { return suggestedAmount; }
    public void setSuggestedAmount(BigDecimal suggestedAmount) { this.suggestedAmount = suggestedAmount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
