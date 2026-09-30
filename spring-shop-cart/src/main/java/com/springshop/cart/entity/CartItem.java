package com.springshop.cart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 购物车条目实体
 *
 * <p>为什么<b>不</b>映射 {@code is_deleted}（不使用逻辑删除）：
 * 表上有唯一键 {@code uk_user_sku(user_id, sku_id)}，若走逻辑删除，
 * 「删除某 SKU 后再加购同一 SKU」会产生新行，与仍存在的旧行（is_deleted=1）
 * 在唯一键上冲突。购物车是临时数据（下单时会做商品快照），物理删除才是正确语义。
 */
@TableName("cart_item")
public class CartItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long skuId;

    private Integer quantity;

    /**
     * 是否勾选：1 勾选 / 0 未勾选
     */
    private Integer checked;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public CartItem() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Integer getChecked() { return checked; }
    public void setChecked(Integer checked) { this.checked = checked; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
