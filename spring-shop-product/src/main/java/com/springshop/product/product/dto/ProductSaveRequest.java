package com.springshop.product.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 商品创建/修改入参
 */
public class ProductSaveRequest {

    @NotNull(message = "分类不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称长度不超过 100")
    private String name;

    @Size(max = 200, message = "副标题长度不超过 200")
    private String subtitle;

    @Size(max = 255, message = "主图长度不超过 255")
    private String mainImage;

    private String detail;

    private Integer status;

    @NotEmpty(message = "SKU 列表不能为空")
    @Valid
    private List<ProductSkuItem> skus;

    private List<ProductImageItem> images;

    public ProductSaveRequest() {
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getMainImage() { return mainImage; }
    public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<ProductSkuItem> getSkus() { return skus; }
    public void setSkus(List<ProductSkuItem> skus) { this.skus = skus; }
    public List<ProductImageItem> getImages() { return images; }
    public void setImages(List<ProductImageItem> images) { this.images = images; }
}
