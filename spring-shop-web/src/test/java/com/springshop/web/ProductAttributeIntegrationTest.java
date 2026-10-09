package com.springshop.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.AttributeSaveRequest;
import com.springshop.product.product.dto.AttributeValueSaveRequest;
import com.springshop.product.product.dto.BrandSaveRequest;
import com.springshop.product.product.entity.ProductAttribute;
import com.springshop.product.product.entity.ProductAttributeValue;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.entity.SkuSpecValue;
import com.springshop.product.product.mapper.ProductAttributeMapper;
import com.springshop.product.product.mapper.ProductAttributeValueMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.mapper.SkuSpecValueMapper;
import com.springshop.product.product.service.AttributeService;
import com.springshop.product.product.service.BrandService;
import com.springshop.product.product.vo.AttributeVO;
import com.springshop.product.product.vo.BrandVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 品牌 / 属性（规格体系）集成测试（真连 H2，跑真实 SQL）
 *
 * <p>为什么必须有这一层：{@link com.springshop.product.product.service.impl.AttributeServiceImplTest}
 * 把 mapper 全 mock 了，{@code sku_spec_value} 的引用查询、{@code attr_value} 列名映射、
 * 以及 {@code orderByAsc(sort)} 的真实排序都不会被执行到。这里用真库把守卫与查询跑一遍。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductAttributeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BrandService brandService;

    @Autowired
    private AttributeService attributeService;

    @Autowired
    private ProductCategoryMapper categoryMapper;

    @Autowired
    private ProductAttributeMapper attributeMapper;

    @Autowired
    private ProductAttributeValueMapper valueMapper;

    @Autowired
    private SkuSpecValueMapper skuSpecValueMapper;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void brand_shouldRoundTripAndRejectDuplicateName() {
        BrandSaveRequest request = new BrandSaveRequest();
        request.setName("集成测试品牌-" + System.nanoTime());

        brandService.create(request);
        List<BrandVO> brands = brandService.list();
        BrandVO created = brands.stream()
                .filter(b -> b.getName().equals(request.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(0, created.getSort(), "默认排序值");
        assertEquals(1, created.getStatus(), "默认启用");
        assertEquals(created.getId(), brandService.getById(created.getId()).getId());

        BusinessException ex = assertThrows(BusinessException.class, () -> brandService.create(request));
        assertEquals(ResultCode.PRODUCT_BRAND_NAME_EXISTS.getCode(), ex.getCode());
    }

    @Test
    void listByCategory_shouldReturnValuesOrderedBySort() {
        long categoryId = insertCategory();
        long attributeId = insertAttribute(categoryId, "颜色");
        insertValue(attributeId, "白", 2);
        insertValue(attributeId, "黑", 1);

        List<AttributeVO> attributes = attributeService.listByCategory(categoryId);

        assertEquals(1, attributes.size());
        AttributeVO color = attributes.get(0);
        assertEquals("颜色", color.getName());
        assertEquals(2, color.getValues().size());
        assertEquals("黑", color.getValues().get(0).getAttrValue(), "sort 小的排前面");
        assertEquals("白", color.getValues().get(1).getAttrValue());
    }

    @Test
    void deleteAttribute_shouldBeRejected_whileSkuStillReferences() {
        long categoryId = insertCategory();
        long attributeId = insertAttribute(categoryId, "颜色");
        long valueId = insertValue(attributeId, "黑", 1);
        long skuId = insertSku();
        linkSkuSpec(skuId, attributeId, valueId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.delete(attributeId));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_HAS_VALUES.getCode(), ex.getCode(),
                "有可选值时先卡在第一道守卫");
    }

    @Test
    void deleteValue_shouldBeRejected_whileSkuStillReferences() {
        long categoryId = insertCategory();
        long attributeId = insertAttribute(categoryId, "颜色");
        long valueId = insertValue(attributeId, "黑", 1);
        long skuId = insertSku();
        linkSkuSpec(skuId, attributeId, valueId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.deleteValue(attributeId, valueId));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_VALUE_IN_USE.getCode(), ex.getCode(),
                "可选值已被 SKU 引用，删除会让 SKU 规格展示缺列");
    }

    @Test
    void deleteValue_shouldSucceed_afterUnlink() {
        long categoryId = insertCategory();
        long attributeId = insertAttribute(categoryId, "颜色");
        long valueId = insertValue(attributeId, "黑", 1);
        long skuId = insertSku();
        linkSkuSpec(skuId, attributeId, valueId);

        skuSpecValueMapper.deleteBySkuId(skuId);
        attributeService.deleteValue(attributeId, valueId);

        assertEquals(0L, countValues(attributeId), "解绑后可选值可正常删除");
    }

    @Test
    void deleteAttribute_shouldBeRejected_whenReferencedBySkuButNoValues() {
        long categoryId = insertCategory();
        long attributeId = insertAttribute(categoryId, "颜色");
        long valueId = insertValue(attributeId, "黑", 1);
        long skuId = insertSku();
        linkSkuSpec(skuId, attributeId, valueId);

        // 把可选值逻辑删除，构造出「属性还在被 SKU 引用、但已无可选值」的极端状态：
        // 第一道守卫（有可选值）不再触发，只能靠第二道（被 SKU 引用）挡住
        valueMapper.deleteById(valueId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.delete(attributeId));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_IN_USE.getCode(), ex.getCode(),
                "没有可选值也要卡在 SKU 引用这道守卫上");
    }

    @Test
    void addValue_shouldRejectDuplicateText() {
        long categoryId = insertCategory();
        long attributeId = insertAttribute(categoryId, "颜色");
        insertValue(attributeId, "黑", 1);

        AttributeValueSaveRequest request = new AttributeValueSaveRequest();
        request.setAttrValue("黑");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.addValue(attributeId, request));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_VALUE_EXISTS.getCode(), ex.getCode());
    }

    @Test
    void skuSpecValue_insertBatch_shouldPersistAllRows() {
        long categoryId = insertCategory();
        long colorId = insertAttribute(categoryId, "颜色");
        long sizeId = insertAttribute(categoryId, "尺寸");
        long blackId = insertValue(colorId, "黑", 1);
        long largeId = insertValue(sizeId, "L", 1);
        long skuId = insertSku();

        LocalDateTime now = LocalDateTime.now();
        List<SkuSpecValue> rows = List.of(
                spec(skuId, colorId, blackId, now),
                spec(skuId, sizeId, largeId, now));
        assertEquals(2, skuSpecValueMapper.insertBatch(rows));

        assertEquals(2L, skuSpecValueMapper.selectCount(
                Wrappers.<SkuSpecValue>lambdaQuery().eq(SkuSpecValue::getSkuId, skuId)));
    }

    @Test
    void adminAttributeApi_shouldRequireLogin() throws Exception {
        mockMvc.perform(get("/api/admin/attributes").param("categoryId", "1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/brands")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private long insertCategory() {
        ProductCategory category = new ProductCategory();
        category.setParentId(0L);
        category.setName("集成测试分类-" + System.nanoTime());
        category.setSort(1);
        category.setStatus(1);
        categoryMapper.insert(category);
        return category.getId();
    }

    private long insertAttribute(long categoryId, String name) {
        ProductAttribute attribute = new ProductAttribute();
        attribute.setCategoryId(categoryId);
        attribute.setName(name);
        attribute.setSort(1);
        attribute.setStatus(1);
        attributeMapper.insert(attribute);
        return attribute.getId();
    }

    private long insertValue(long attributeId, String text, int sort) {
        ProductAttributeValue value = new ProductAttributeValue();
        value.setAttributeId(attributeId);
        value.setAttrValue(text);
        value.setSort(sort);
        valueMapper.insert(value);
        return value.getId();
    }

    private long insertSku() {
        ProductSku sku = new ProductSku();
        sku.setProductId(1L);
        sku.setSkuCode("SKU-ATTR-" + System.nanoTime());
        sku.setSpecs("颜色:黑");
        sku.setPrice(new BigDecimal("19.90"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);
        return sku.getId();
    }

    private void linkSkuSpec(long skuId, long attributeId, long valueId) {
        SkuSpecValue link = spec(skuId, attributeId, valueId, LocalDateTime.now());
        skuSpecValueMapper.insert(link);
    }

    private SkuSpecValue spec(long skuId, long attributeId, long valueId, LocalDateTime createTime) {
        SkuSpecValue link = new SkuSpecValue();
        link.setSkuId(skuId);
        link.setAttributeId(attributeId);
        link.setAttributeValueId(valueId);
        link.setCreateTime(createTime);
        return link;
    }

    private long countValues(long attributeId) {
        Long count = valueMapper.selectCount(
                Wrappers.<ProductAttributeValue>lambdaQuery()
                        .eq(ProductAttributeValue::getAttributeId, attributeId));
        return count == null ? 0L : count;
    }
}
