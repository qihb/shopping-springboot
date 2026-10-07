package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 商品图片出参
 */
@Schema(description = "商品图片")
public class ProductImageVO {

    @Schema(description = "图片 id")
    private Long id;

    @Schema(description = "图片 URL")
    private String imageUrl;

    @Schema(description = "排序值")
    private Integer sort;

    public ProductImageVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
