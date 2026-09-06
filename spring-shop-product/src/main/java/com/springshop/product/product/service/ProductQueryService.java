package com.springshop.product.product.service;

import com.springshop.common.result.PageResult;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;

/**
 * 商品读服务
 */
public interface ProductQueryService {

    PageResult<ProductListVO> adminPage(ProductPageQuery query);

    ProductDetailVO adminDetail(Long id);

    PageResult<ProductListVO> appPage(ProductPageQuery query);

    ProductDetailVO appDetail(Long id);
}
