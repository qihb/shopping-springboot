package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private ProductQueryServiceImpl productQueryService;

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
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(buildCategory(5L, "数码")));
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
