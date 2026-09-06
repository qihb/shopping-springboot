package com.springshop.product.product.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情出参（后台/前台通用，前台仅在上架状态返回）
 */
public class ProductDetailVO {

    private Long id;

    private Long categoryId;

    private String categoryName;

    private String name;

    private String subtitle;

    private String mainImage;

    private String detail;

    private BigDecimal minPrice;

    private Integer sales;

    private Integer status;

    private List<ProductSkuVO> skus;

    private List<ProductImageVO> images;

    public ProductDetailVO() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public BigDecimal getMinPrice() { return minPrice; }
    public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }
    public Integer getSales() { return sales; }
    public void setSales(Integer sales) { this.sales = sales; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<ProductSkuVO> getSkus() { return skus; }
    public void setSkus(List<ProductSkuVO> skus) { this.skus = skus; }
    public List<ProductImageVO> getImages() { return images; }
    public void setImages(List<ProductImageVO> images) { this.images = images; }
}
