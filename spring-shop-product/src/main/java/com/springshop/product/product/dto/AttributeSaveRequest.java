package com.springshop.product.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 商品属性（规格定义）保存请求
 */
@Schema(description = "商品属性保存入参")
public class AttributeSaveRequest {

    @Schema(description = "所属分类 id", example = "1")
    @NotNull(message = "所属分类 id 不能为空")
    private Long categoryId;

    @Schema(description = "属性名称，如「颜色」「尺寸」", example = "颜色")
    @NotBlank(message = "属性名称不能为空")
    private String name;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：1 启用 / 0 停用", example = "1")
    private Integer status;

    public AttributeSaveRequest() {
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
