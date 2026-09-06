package com.springshop.product.product.service;

import com.springshop.product.product.dto.ProductSaveRequest;

/**
 * 商品写服务
 */
public interface ProductManageService {

    Long create(ProductSaveRequest request);

    void update(Long id, ProductSaveRequest request);

    void updateStatus(Long id, Integer status);
}
