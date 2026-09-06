package com.springshop.product.product.dto;

import com.springshop.common.dto.PageQuery;

/**
 * 商品分页查询入参
 */
public class ProductPageQuery extends PageQuery {

    private Long categoryId;

    private String keyword;

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
