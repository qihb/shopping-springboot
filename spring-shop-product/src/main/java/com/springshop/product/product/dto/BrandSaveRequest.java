package com.springshop.product.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 品牌保存请求
 */
@Schema(description = "品牌保存入参")
public class BrandSaveRequest {

    @Schema(description = "品牌名称", example = "苹果")
    @NotBlank(message = "品牌名称不能为空")
    private String name;

    @Schema(description = "品牌 logo 地址")
    private String logo;

    @Schema(description = "排序值，越小越靠前", example = "1")
    private Integer sort;

    @Schema(description = "状态：1 启用 / 0 停用", example = "1")
    private Integer status;

    public BrandSaveRequest() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLogo() { return logo; }
    public void setLogo(String logo) { this.logo = logo; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
