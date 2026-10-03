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
     * @param current  页码，从 1 开始
     * @param pageSize 每页条数
     */
    List<ProductListVO> adminExportPage(ProductExportQuery query, long current, long pageSize);

    ProductDetailVO adminDetail(Long id);

    PageResult<ProductListVO> appPage(ProductPageQuery query);

    ProductDetailVO appDetail(Long id);
}
