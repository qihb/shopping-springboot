package com.springshop.product.category.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 分类 VO（后台列表扁平用）
 */
@Schema(description = "分类详情")
public class CategoryVO {

    @Schema(description = "分类 id")
    private Long id;

    @Schema(description = "父分类 id")
    private Long parentId;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "状态")
    private Integer status;

    public CategoryVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
