package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.OccupiedProductSpec;
import com.springshop.product.product.dto.ProductSaveRequest;
import com.springshop.product.product.dto.ProductSkuItem;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.InventoryService;
import com.springshop.product.product.service.ProductManageService;
import com.springshop.product.product.vo.InventoryVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock
    private ProductImageMapper productImageMapper;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

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

    @Test
    void update_should_evict_detail_cache() {
        ProductSaveRequest request = buildCreateRequest();
        Product product = new Product();
        product.setId(100L);
        when(productMapper.selectById(100L)).thenReturn(product);
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productSkuMapper.insert(any(ProductSku.class))).thenReturn(1);
        productManageService.update(100L, request);

        verify(stringRedisTemplate).delete(RedisKeys.productDetail(100L));
    }

    @Test
    void updateStatus_should_evict_detail_cache() {
        Product product = new Product();
        product.setId(100L);
        when(productMapper.selectById(100L)).thenReturn(product);
        productManageService.updateStatus(100L, 0);

        verify(stringRedisTemplate).delete(RedisKeys.productDetail(100L));
    }

    @Test
    void create_should_not_evict_detail_cache() {
        ProductSaveRequest request = buildCreateRequest();
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            p.setId(100L);
            return 1;
        });
        when(productSkuMapper.insert(any(ProductSku.class))).thenReturn(1);
        productManageService.create(request);

        // 新商品不可能有旧缓存，不执行 DEL
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void create_should_fail_when_identity_duplicate_against_db() {
        ProductSaveRequest request = buildCreateRequest();
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productMapper.selectOccupiedProductSpecs(any())).thenReturn(List.of(
                occupied(9L, "测试商品", "颜色:黑")));

        BusinessException ex = assertThrows(BusinessException.class, () -> productManageService.create(request));

        assertEquals(ResultCode.PRODUCT_IDENTITY_DUPLICATE.getCode(), ex.getCode());
        verify(productMapper, never()).insert(any(Product.class));
    }

    /**
     * 本次提交内部重复：{@code validateSkuCodesUnique} 只查 {@code sku_code}，拦不住这个。
     *
     * <p>规格写成全角冒号也要被认成同一条 —— 规范化是唯一性口径的一部分。
     */
    @Test
    void create_should_fail_when_specs_duplicate_inside_request() {
        ProductSaveRequest request = buildCreateRequest();
        ProductSkuItem second = new ProductSkuItem();
        second.setSkuCode("SKU-002");
        second.setSpecs("颜色：黑");
        second.setPrice(new BigDecimal("88.00"));
        second.setStock(5);
        second.setStatus(1);
        request.setSkus(List.of(request.getSkus().get(0), second));

        BusinessException ex = assertThrows(BusinessException.class, () -> productManageService.create(request));

        assertEquals(ResultCode.PRODUCT_IDENTITY_DUPLICATE.getCode(), ex.getCode());
        verify(productMapper, never()).insert(any(Product.class));
    }

    @Test
    void update_should_fail_when_identity_conflicts_with_other_product() {
        ProductSaveRequest request = buildCreateRequest();
        Product product = new Product();
        product.setId(100L);
        when(productMapper.selectById(100L)).thenReturn(product);
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productMapper.selectOccupiedProductSpecs(any())).thenReturn(List.of(
                occupied(9L, "测试商品", "颜色:黑")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productManageService.update(100L, request));

        assertEquals(ResultCode.PRODUCT_IDENTITY_DUPLICATE.getCode(), ex.getCode());
        verify(productMapper, never()).updateById(any(Product.class));
    }

    /**
     * 修改商品时必须按「商品粒度」排除自己，否则同一个商品自己的规格会把它自己挡住。
     */
    @Test
    void update_should_notTreatOwnProductAsConflict() {
        ProductSaveRequest request = buildCreateRequest();
        Product product = new Product();
        product.setId(100L);
        when(productMapper.selectById(100L)).thenReturn(product);
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(productMapper.selectOccupiedProductSpecs(any())).thenReturn(List.of(
                occupied(100L, "测试商品", "颜色:黑")));
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());

        productManageService.update(100L, request);

        verify(productMapper).updateById(any(Product.class));
    }

    /**
     * Bug A / C 的回归防线：规格相同的 SKU 必须<b>就地更新（保留 id）</b>，而不是「删掉重建」。
     * 后者会撞 {@code uk_sku_code}（改商品必 500），并让 {@code cart_item.sku_id} 变成脏引用。
     */
    @Test
    void update_shouldUpdateExistingSkuInPlace_andNeverDeleteAll() {
        ProductSaveRequest request = buildCreateRequest();
        Product product = new Product();
        product.setId(100L);
        when(productMapper.selectById(100L)).thenReturn(product);
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());

        ProductSku existing = new ProductSku();
        existing.setId(555L);
        existing.setProductId(100L);
        existing.setSkuCode("SKU-001");
        existing.setSpecs("颜色:黑");
        when(productSkuMapper.selectList(any())).thenReturn(List.of(existing));
        when(inventoryService.getBySkuId(555L)).thenReturn(new InventoryVO(555L, 10, 0));

        productManageService.update(100L, request);

        ArgumentCaptor<ProductSku> captor = ArgumentCaptor.forClass(ProductSku.class);
        verify(productSkuMapper).updateById(captor.capture());
        assertEquals(555L, captor.getValue().getId(), "必须保留原 sku_id");
        verify(productSkuMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(productSkuMapper, never()).insert(any(ProductSku.class));
        // 库存值没变，不该留下一条无意义的调整流水
        verify(inventoryService, never()).adjust(anyLong(), anyInt(), any(), anyString());
    }

    /**
     * Bug B 的回归防线：{@code ProductSkuItem.stock} 没有 {@code @NotNull}，
     * 前端不传库存是常态，此时必须<b>保持原值</b>而不是当成 0（否则商品被悄悄改成不可售）。
     */
    @Test
    void update_shouldKeepStock_whenItemStockIsNull() {
        ProductSaveRequest request = buildCreateRequest();
        request.getSkus().get(0).setStock(null);
        Product product = new Product();
        product.setId(100L);
        when(productMapper.selectById(100L)).thenReturn(product);
        when(productSkuMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(categoryMapper.selectById(1L)).thenReturn(new ProductCategory());

        ProductSku existing = new ProductSku();
        existing.setId(555L);
        existing.setProductId(100L);
        existing.setSkuCode("SKU-001");
        existing.setSpecs("颜色:黑");
        when(productSkuMapper.selectList(any())).thenReturn(List.of(existing));

        productManageService.update(100L, request);

        // SKU 表自 V10 起已无库存字段，「保持原值」这件事完全落在「不去动库存表」上：
        // 不 adjust 就不会有新流水，原值自然保留。所以这里断言的是行为，不是某个字段的值。
        verify(productSkuMapper).updateById(any(ProductSku.class));
        verify(inventoryService, never()).adjust(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    void delete_should_fail_when_product_not_found() {
        when(productMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productManageService.delete(999L));

        assertEquals(ResultCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }

    /**
     * 上架商品可能正被下单 / 加购，直接删除会让前台出现「列表里没有、详情却还能打开」的中间态。
     */
    @Test
    void delete_should_fail_when_product_not_off_shelf() {
        Product product = new Product();
        product.setId(100L);
        product.setStatus(1);
        when(productMapper.selectById(100L)).thenReturn(product);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productManageService.delete(100L));

        assertEquals(ResultCode.PRODUCT_NOT_OFF_SHELF.getCode(), ex.getCode());
        verify(productSkuMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(productImageMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void delete_should_cascadeLogicalDelete_andEvictDetailCache() {
        Product product = new Product();
        product.setId(100L);
        product.setStatus(0);
        when(productMapper.selectById(100L)).thenReturn(product);

        productManageService.delete(100L);

        // 残留 SKU 的 sku_code 仍占着全局唯一编码，必须连带删掉
        verify(productSkuMapper).delete(any(LambdaQueryWrapper.class));
        verify(productImageMapper).delete(any(LambdaQueryWrapper.class));
        verify(productMapper).deleteById(100L);
        verify(stringRedisTemplate).delete(RedisKeys.productDetail(100L));
    }

    private OccupiedProductSpec occupied(Long productId, String name, String specs) {
        OccupiedProductSpec spec = new OccupiedProductSpec();
        spec.setProductId(productId);
        spec.setName(name);
        spec.setSpecs(specs);
        return spec;
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
