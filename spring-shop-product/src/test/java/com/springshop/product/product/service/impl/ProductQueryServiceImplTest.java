package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.ProductPageQuery;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductImage;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductImageMapper;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.ProductQueryService;
import com.springshop.product.product.vo.ProductDetailVO;
import com.springshop.product.product.vo.ProductListVO;
import com.springshop.product.product.vo.ProductSkuVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductQueryServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductImageMapper productImageMapper;

    @Mock
    private ProductCategoryMapper categoryMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProductQueryServiceImpl productQueryService;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        org.mockito.Mockito.when(stringRedisTemplate.opsForValue()).thenReturn(ops);
    }

    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] { Product.class, ProductSku.class, ProductImage.class, ProductCategory.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    @Test
    void adminDetail_should_fail_when_not_found() {
        when(productMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productQueryService.adminDetail(999L));
        assertEquals(ResultCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void appDetail_should_fail_when_product_not_found() {
        when(productMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productQueryService.appDetail(999L));
        assertEquals(ResultCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void appDetail_should_fail_when_off_shelf() {
        Product p = buildProduct(100L, 0);
        when(productMapper.selectById(100L)).thenReturn(p);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productQueryService.appDetail(100L));
        assertEquals(ResultCode.PRODUCT_OFF_SHELF.getCode(), ex.getCode());
    }

    @Test
    void appDetail_should_return_cache_when_hit() throws Exception {
        ProductDetailVO cachedVo = new ProductDetailVO();
        cachedVo.setId(100L);
        cachedVo.setName("缓存商品");
        when(stringRedisTemplate.opsForValue().get(RedisKeys.productDetail(100L))).thenReturn("{\"id\":100}");
        when(objectMapper.readValue("{\"id\":100}", ProductDetailVO.class)).thenReturn(cachedVo);

        ProductDetailVO vo = productQueryService.appDetail(100L);

        assertEquals("缓存商品", vo.getName());
        // 缓存命中时不应查库
        verify(productMapper, never()).selectById(100L);
    }

    @Test
    void appDetail_should_query_db_and_fill_cache_when_miss() throws Exception {
        Product p = buildProduct(100L, 1);
        when(stringRedisTemplate.opsForValue().get(RedisKeys.productDetail(100L))).thenReturn(null);
        when(productMapper.selectById(100L)).thenReturn(p);
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(productImageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any(ProductDetailVO.class))).thenReturn("{\"id\":100}");

        ProductDetailVO vo = productQueryService.appDetail(100L);

        assertNotNull(vo);
        // 回填缓存，TTL 基础 30 分钟 + 0~5 分钟随机抖动
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(stringRedisTemplate.opsForValue()).set(
                eq(RedisKeys.productDetail(100L)), eq("{\"id\":100}"), ttlCaptor.capture());
        long minutes = ttlCaptor.getValue().toMinutes();
        assertTrue(minutes >= 30 && minutes <= 35);
    }

    @Test
    void appDetail_should_cache_null_marker_when_not_found() {
        when(stringRedisTemplate.opsForValue().get(RedisKeys.productDetail(999L))).thenReturn(null);
        when(productMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productQueryService.appDetail(999L));
        assertEquals(ResultCode.PRODUCT_NOT_FOUND.getCode(), ex.getCode());
        // 空值短缓存 60s 防穿透
        verify(stringRedisTemplate.opsForValue()).set(
                eq(RedisKeys.productDetail(999L)), eq("NULL"), eq(Duration.ofSeconds(60)));
    }

    @Test
    void appDetail_should_not_cache_when_off_shelf() {
        Product p = buildProduct(100L, 0);
        when(stringRedisTemplate.opsForValue().get(RedisKeys.productDetail(100L))).thenReturn(null);
        when(productMapper.selectById(100L)).thenReturn(p);

        assertThrows(BusinessException.class, () -> productQueryService.appDetail(100L));

        // 下架商品不写缓存（管理端可能马上重新上架）
        verify(stringRedisTemplate.opsForValue(), never()).set(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void adminDetail_should_return_aggregated_vo() {
        Product p = buildProduct(100L, 1);
        p.setCategoryId(5L);
        when(productMapper.selectById(100L)).thenReturn(p);
        when(categoryMapper.selectById(5L)).thenReturn(buildCategory(5L, "数码"));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                buildSku(1L, 100L, "S1", new BigDecimal("100.00")),
                buildSku(2L, 100L, "S2", new BigDecimal("80.00"))
        ));
        when(productImageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                buildImage(1L, 100L, "url1", 1)
        ));

        ProductDetailVO vo = productQueryService.adminDetail(100L);

        assertNotNull(vo);
        assertEquals("数码", vo.getCategoryName());
        assertEquals(new BigDecimal("80.00"), vo.getMinPrice());
        assertEquals(2, vo.getSkus().size());
        List<ProductSkuVO> sorted = vo.getSkus();
        assertEquals(new BigDecimal("80.00"), sorted.get(0).getPrice());
        assertEquals(1, vo.getImages().size());
    }

    @Test
    void adminPage_should_return_paginated_records_with_min_price_and_category_name() {
        ProductPageQuery query = new ProductPageQuery();
        query.setCurrent(1L);
        query.setSize(10L);
        query.setCategoryId(5L);
        query.setKeyword("耳机");
        query.setStatus(1);

        Page<Product> page = new Page<>(1, 10);
        Product p = buildProduct(100L, 1);
        p.setCategoryId(5L);
        page.setRecords(List.of(p));
        page.setTotal(1L);

        when(productMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(categoryMapper.selectById(5L)).thenReturn(buildCategory(5L, "数码"));
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildCategory(5L, "数码")));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                buildSku(1L, 100L, "S1", new BigDecimal("100.00")),
                buildSku(2L, 100L, "S2", new BigDecimal("80.00"))
        ));

        PageResult<ProductListVO> result = productQueryService.adminPage(query);

        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        ProductListVO row = result.getRecords().get(0);
        assertEquals("数码", row.getCategoryName());
        assertEquals(new BigDecimal("80.00"), row.getMinPrice());
    }

    private Product buildProduct(Long id, Integer status) {
        Product p = new Product();
        p.setId(id);
        p.setName("测试商品");
        p.setStatus(status);
        return p;
    }

    private ProductCategory buildCategory(Long id, String name) {
        ProductCategory c = new ProductCategory();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private ProductSku buildSku(Long id, Long productId, String code, BigDecimal price) {
        ProductSku s = new ProductSku();
        s.setId(id);
        s.setProductId(productId);
        s.setSkuCode(code);
        s.setPrice(price);
        s.setStatus(1);
        return s;
    }

    private ProductImage buildImage(Long id, Long productId, String url, Integer sort) {
        ProductImage image = new ProductImage();
        image.setId(id);
        image.setProductId(productId);
        image.setImageUrl(url);
        image.setSort(sort);
        return image;
    }
}
