package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 品牌出参
 */
@Schema(description = "品牌信息")
public class BrandVO {

    @Schema(description = "品牌 id")
    private Long id;

    @Schema(description = "品牌名称")
    private String name;

    @Schema(description = "品牌 logo 地址")
    private String logo;

    @Schema(description = "排序值")
    private Integer sort;

    @Schema(description = "状态：1 启用 / 0 停用")
    private Integer status;

    public BrandVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLogo() { return logo; }
    public void setLogo(String logo) { this.logo = logo; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
