package com.springshop.product.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * SKU 规格值关联实体（SKU ↔ 属性值，多对多）
 *
 * <p>纯关联表：不带 {@code is_deleted} / {@code version}（与 {@code admin_user_role} / {@code role_menu} 一致），
 * 规格变更时按 SKU 物理删除重建。
 */
@TableName("sku_spec_value")
public class SkuSpecValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    private Long attributeId;

    private Long attributeValueId;

    private LocalDateTime createTime;

    public SkuSpecValue() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getAttributeId() { return attributeId; }
    public void setAttributeId(Long attributeId) { this.attributeId = attributeId; }
    public Long getAttributeValueId() { return attributeValueId; }
    public void setAttributeValueId(Long attributeValueId) { this.attributeValueId = attributeValueId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
