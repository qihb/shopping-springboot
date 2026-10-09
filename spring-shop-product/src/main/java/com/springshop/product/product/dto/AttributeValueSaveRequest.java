package com.springshop.product.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 商品属性可选值保存请求
 */
@Schema(description = "商品属性可选值保存入参")
public class AttributeValueSaveRequest {

    @Schema(description = "可选值文本，如「黑」「L」", example = "黑")
    @NotBlank(message = "可选值不能为空")
    private String attrValue;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    public AttributeValueSaveRequest() {
    }

    public String getAttrValue() { return attrValue; }
    public void setAttrValue(String attrValue) { this.attrValue = attrValue; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
