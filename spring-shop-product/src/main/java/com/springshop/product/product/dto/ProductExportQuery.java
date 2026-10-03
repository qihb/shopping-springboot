package com.springshop.product.product.dto;

import java.util.List;

/**
 * 商品导出查询条件
 *
 * <p>刻意不继承 {@code ProductPageQuery}：导出没有分页概念，
 * 继承过来只会带上 current/size 两个无用字段，还会被 {@code @Max(100)} 的校验规则误导
 * （导出本来就该突破单页 100 条的限制，分页由后台线程按 {@code export-page-size} 自己控制）。
 */
public class ProductExportQuery {

    /** 分类筛选 */
    private Long categoryId;

    /** 商品名称关键字（模糊匹配） */
    private String keyword;

    /** 上下架状态：1 上架 / 0 下架；为空表示不限 */
    private Integer status;

    /**
     * 指定商品 id 列表（前端勾选若干行后「导出选中」）
     *
     * <p>为空表示按上面的筛选条件导出全部匹配数据。
     */
    private List<Long> ids;

    public ProductExportQuery() {
    }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }
}
