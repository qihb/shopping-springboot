package com.springshop.product.product.service;

import com.springshop.product.product.dto.ProductSaveRequest;

/**
 * 商品写服务
 */
public interface ProductManageService {

    Long create(ProductSaveRequest request);

    void update(Long id, ProductSaveRequest request);

    void updateStatus(Long id, Integer status);

    /**
     * 删除商品（逻辑删除，<b>仅下架商品可删</b>）
     *
     * <p>连带逻辑删除该商品的全部 SKU 与图片：残留 SKU 的 {@code sku_code}
     * 仍占着全局唯一编码，会让该编码以后永远不能再用。
     *
     * @throws com.springshop.common.exception.BusinessException 商品不存在（2010）
     *         或未下架（2016）
     */
    void delete(Long id);
}
