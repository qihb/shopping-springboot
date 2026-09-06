package com.springshop.product.product.vo;

/**
 * 商品图片出参
 */
public class ProductImageVO {

    private Long id;

    private String imageUrl;

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
