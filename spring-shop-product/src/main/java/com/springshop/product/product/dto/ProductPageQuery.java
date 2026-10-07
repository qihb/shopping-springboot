package com.springshop.product.product.dto;

import com.springshop.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 商品分页查询入参
 */
@Schema(description = "商品分页查询入参")
public class ProductPageQuery extends PageQuery {

    @Schema(description = "分类 id")
    private Long categoryId;

    @Schema(description = "商品名称关键字（模糊匹配）")
    private String keyword;

    @Schema(description = "上架状态：1-上架，0-下架")
    private Integer status;

    public ProductPageQuery() {
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
