package com.springshop.product.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 商品创建/修改入参
 */
@Schema(description = "商品创建/修改入参")
public class ProductSaveRequest {

    @Schema(description = "分类 id")
    @NotNull(message = "分类不能为空")
    private Long categoryId;

    @Schema(description = "商品名称，长度不超过 100")
    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称长度不超过 100")
    private String name;

    @Schema(description = "副标题，长度不超过 200")
    @Size(max = 200, message = "副标题长度不超过 200")
    private String subtitle;

    @Schema(description = "主图 URL，长度不超过 255")
    @Size(max = 255, message = "主图长度不超过 255")
    private String mainImage;

    @Schema(description = "商品详情（富文本 / HTML）")
    private String detail;

    @Schema(description = "上架状态：1-上架，0-下架")
    private Integer status;

    @Schema(description = "SKU 列表，至少一个")
    @NotEmpty(message = "SKU 列表不能为空")
    @Valid
    private List<ProductSkuItem> skus;

    @Schema(description = "商品图片列表")
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
