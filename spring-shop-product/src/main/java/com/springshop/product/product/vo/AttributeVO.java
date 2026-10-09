package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 商品属性出参（含可选值列表）
 */
@Schema(description = "商品属性（规格定义）")
public class AttributeVO {

    @Schema(description = "属性 id")
    private Long id;

    @Schema(description = "所属分类 id")
    private Long categoryId;

    @Schema(description = "属性名称")
    private String name;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "状态：1 启用 / 0 停用")
    private Integer status;

    @Schema(description = "可选值列表")
    private List<AttributeValueVO> values;

    public AttributeVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<AttributeValueVO> getValues() { return values; }
    public void setValues(List<AttributeValueVO> values) { this.values = values; }
}
