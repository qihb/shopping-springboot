package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.dto.ProductSkuItem;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.ProductManageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductManageServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductCategoryMapper categoryMapper;

    @InjectMocks
    private ProductManageServiceImpl productManageService;

    @Test
    void create_should_fail_when_skus_empty() {
        ProductSaveRequest request = buildCreateRequest();
        request.setSkus(Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class, () -> productManageService.create(request));

        assertEquals(ResultCode.PRODUCT_SKU_EMPTY.getCode(), ex.getCode());
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void create_should_fail_when_category_not_found() {
        ProductSaveRequest request = buildCreateRequest();
        when(categoryMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> productManageService.create(request));

        assertEquals(ResultCode.PRODUCT_CATEGORY_NOT_FOUND.getCode(), ex.getCode());
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void create_should_fail_when_sku_code_duplicate_across_products() {
        ProductSaveRequest request = buildCreateRequest();
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> productManageService.create(request));

        assertEquals(ResultCode.PRODUCT_SKU_CODE_DUPLICATE.getCode(), ex.getCode());
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void create_should_save_product_and_skus_and_images() {
        ProductSaveRequest request = buildCreateRequest();
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            p.setId(100L);
            return 1;
        });
        when(productSkuMapper.insert(any(ProductSku.class))).thenReturn(1);

        Long productId = productManageService.create(request);

        assertNotNull(productId);
        assertEquals(100L, productId);
        verify(productMapper).insert(any(Product.class));
        ArgumentCaptor<List<ProductSku>> skuListCaptor = ArgumentCaptor.forClass(List.class);
        verify(productSkuMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(productSkuMapper).insert(any(ProductSku.class));
    }

    @Test
    void updateStatus_should_fail_when_product_not_found() {
        when(productMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productManageService.updateStatus(999L, 0));

        assertEquals(ResultCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }

    private ProductSaveRequest buildCreateRequest() {
        ProductSaveRequest r = new ProductSaveRequest();
        r.setCategoryId(1L);
        r.setName("测试商品");
        r.setStatus(1);

        ProductSkuItem sku = new ProductSkuItem();
        sku.setSkuCode("SKU-001");
        sku.setSpecs("颜色:黑");
        sku.setPrice(new BigDecimal("99.99"));
        sku.setStock(10);
        sku.setStatus(1);
        r.setSkus(List.of(sku));
        return r;
    }
}
