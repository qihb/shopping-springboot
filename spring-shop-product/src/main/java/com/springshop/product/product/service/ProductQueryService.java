package com.springshop.product.product.service;

import com.springshop.common.result.PageResult;
import com.springshop.product.product.dto.ProductExportQuery;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;

import java.util.List;

/**
 * 商品读服务
 */
public interface ProductQueryService {

    PageResult<ProductListVO> adminPage(ProductPageQuery query);

    /**
     * 导出一页数据
     *
     * <p>与 {@link #adminPage} 分开是因为导出要突破「单页最多 100 条」的接口层限制，
     * 且不需要总数（导出按「还有没有下一页」推进即可，多一次 COUNT 纯属浪费）。
     *
     * @param lastId   keyset 游标：上一页最后一条的 id，{@code null} 表示从第一页开始。
     *                 按 id 倒序取 {@code id < lastId}。不能用 OFFSET 页码——
     *                 导出要跑几分钟，期间有人新增商品会让后续页窗口整体后移，
     *                 导致已导出的行重复、边缘的行被整页跳过
     * @param pageSize 每页条数
     */
    List<ProductListVO> adminExportPage(ProductExportQuery query, Long lastId, long pageSize);

    ProductDetailVO adminDetail(Long id);

    PageResult<ProductListVO> appPage(ProductPageQuery query);

    ProductDetailVO appDetail(Long id);
}
