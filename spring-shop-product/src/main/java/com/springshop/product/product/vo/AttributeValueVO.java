package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 商品属性可选值出参
 */
@Schema(description = "商品属性可选值")
public class AttributeValueVO {

    @Schema(description = "可选值 id")
    private Long id;

    @Schema(description = "所属属性 id")
    private Long attributeId;

    @Schema(description = "可选值文本")
    private String attrValue;

    @Schema(description = "排序值")
    private Integer sort;

    public AttributeValueVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAttributeId() { return attributeId; }
    public void setAttributeId(Long attributeId) { this.attributeId = attributeId; }
    public String getAttrValue() { return attrValue; }
    public void setAttrValue(String attrValue) { this.attrValue = attrValue; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
