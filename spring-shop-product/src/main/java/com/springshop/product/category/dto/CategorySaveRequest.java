package com.springshop.product.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 分类保存请求
 */
@Schema(description = "商品分类保存入参")
public class CategorySaveRequest {

    @Schema(description = "父分类 id，顶级传 0", example = "0")
    @NotNull(message = "父分类 id 不能为空")
    private Long parentId;

    @Schema(description = "分类名称", example = "数码")
    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：1 启用 / 0 停用", example = "1")
    private Integer status;

    public CategorySaveRequest() {
    }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
