package com.springshop.product.product.service.impl;

import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.product.dto.BrandSaveRequest;
import com.springshop.product.product.entity.Brand;
import com.springshop.product.product.mapper.BrandMapper;
import com.springshop.product.product.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 品牌服务单测：钉住「同名拦截 + 有商品不可删」两条守卫
 *
 * <p>品牌名没有唯一索引能兜住逻辑删除（删掉的品牌会永久占名），
 * 因此应用层这道校验是<b>唯一</b>的防线，必须有单测钉住。
 */
@ExtendWith(MockitoExtension.class)
class BrandServiceImplTest {

    private static final long BRAND_ID = 7L;

    @Mock
    private BrandMapper brandMapper;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private BrandServiceImpl brandService;

    @Test
    void create_shouldRejectDuplicateName() {
        when(brandMapper.selectCount(any())).thenReturn(1L);

        BrandSaveRequest request = request("苹果");

        BusinessException ex = assertThrows(BusinessException.class, () -> brandService.create(request));
        assertEquals(ResultCode.PRODUCT_BRAND_NAME_EXISTS.getCode(), ex.getCode());
        verify(brandMapper, never()).insert(any(Brand.class));
    }

    @Test
    void create_shouldDefaultSortAndStatus_whenOmitted() {
        when(brandMapper.selectCount(any())).thenReturn(0L);

        brandService.create(request("苹果"));

        Brand saved = captureInsert();
        assertEquals("苹果", saved.getName());
        assertEquals(0, saved.getSort(), "未传 sort 时默认 0");
        assertEquals(1, saved.getStatus(), "未传 status 时默认启用");
    }

    @Test
    void create_shouldTrimName() {
        when(brandMapper.selectCount(any())).thenReturn(0L);

        brandService.create(request("  苹果  "));

        assertEquals("苹果", captureInsert().getName(), "首尾空白应在落库前去掉");
    }

    @Test
    void update_shouldAllowKeepingOwnName() {
        Brand existing = brand(BRAND_ID, "苹果");
        when(brandMapper.selectById(BRAND_ID)).thenReturn(existing);
        when(brandMapper.selectCount(any())).thenReturn(0L);

        brandService.update(BRAND_ID, request("苹果"));

        verify(brandMapper).updateById(existing);
        assertEquals("苹果", existing.getName());
    }

    @Test
    void update_shouldRejectNameTakenByAnother() {
        when(brandMapper.selectById(BRAND_ID)).thenReturn(brand(BRAND_ID, "苹果"));
        when(brandMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> brandService.update(BRAND_ID, request("小米")));
        assertEquals(ResultCode.PRODUCT_BRAND_NAME_EXISTS.getCode(), ex.getCode());
        verify(brandMapper, never()).updateById(any(Brand.class));
    }

    @Test
    void delete_shouldReject_whenProductsStillReference() {
        when(brandMapper.selectById(BRAND_ID)).thenReturn(brand(BRAND_ID, "苹果"));
        when(productMapper.selectCount(any())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class, () -> brandService.delete(BRAND_ID));
        assertEquals(ResultCode.PRODUCT_BRAND_HAS_PRODUCTS.getCode(), ex.getCode());
        verify(brandMapper, never()).deleteById(BRAND_ID);
    }

    @Test
    void delete_shouldSucceed_whenNoProductReferences() {
        when(brandMapper.selectById(BRAND_ID)).thenReturn(brand(BRAND_ID, "苹果"));
        when(productMapper.selectCount(any())).thenReturn(0L);

        brandService.delete(BRAND_ID);

        verify(brandMapper).deleteById(BRAND_ID);
    }

    @Test
    void getById_shouldThrowNotFound_whenMissing() {
        when(brandMapper.selectById(BRAND_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> brandService.getById(BRAND_ID));
        assertEquals(ResultCode.PRODUCT_BRAND_NOT_FOUND.getCode(), ex.getCode());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private BrandSaveRequest request(String name) {
        BrandSaveRequest request = new BrandSaveRequest();
        request.setName(name);
        return request;
    }

    private Brand brand(Long id, String name) {
        Brand brand = new Brand();
        brand.setId(id);
        brand.setName(name);
        return brand;
    }

    private Brand captureInsert() {
        ArgumentCaptor<Brand> captor = ArgumentCaptor.forClass(Brand.class);
        verify(brandMapper).insert(captor.capture());
        return captor.getValue();
    }
}
