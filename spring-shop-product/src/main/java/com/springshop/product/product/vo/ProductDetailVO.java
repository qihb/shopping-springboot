package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情出参（后台/前台通用，前台仅在上架状态返回）
 */
@Schema(description = "商品详情")
public class ProductDetailVO {

    @Schema(description = "商品 id")
    private Long id;

    @Schema(description = "分类 id")
    private Long categoryId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "商品名称")
    private String name;

    @Schema(description = "副标题")
    private String subtitle;

    @Schema(description = "主图 URL")
    private String mainImage;

    @Schema(description = "商品详情（富文本 / HTML）")
    private String detail;

    @Schema(description = "最低销售价（元）")
    private BigDecimal minPrice;

    @Schema(description = "销量")
    private Integer sales;

    @Schema(description = "上架状态：1-上架，0-下架")
    private Integer status;

    @Schema(description = "SKU 列表")
    private List<ProductSkuVO> skus;

    @Schema(description = "商品图片列表")
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
