package com.springshop.product.product.dto;

/**
 * 商品图片保存项
 */
public class ProductImageItem {

    private Long id;

    private String imageUrl;

    private Integer sort;

    public ProductImageItem() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
