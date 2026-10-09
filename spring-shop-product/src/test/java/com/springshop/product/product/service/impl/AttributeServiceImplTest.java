package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.product.category.entity.ProductCategory;
import com.springshop.product.category.mapper.ProductCategoryMapper;
import com.springshop.product.product.dto.AttributeSaveRequest;
import com.springshop.product.product.dto.AttributeValueSaveRequest;
import com.springshop.product.product.entity.ProductAttribute;
import com.springshop.product.product.entity.ProductAttributeValue;
import com.springshop.product.product.entity.SkuSpecValue;
import com.springshop.product.product.mapper.ProductAttributeMapper;
import com.springshop.product.product.mapper.ProductAttributeValueMapper;
import com.springshop.product.product.mapper.SkuSpecValueMapper;
import com.springshop.product.product.vo.AttributeVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品属性服务单测：钉住删除守卫与「可选值必须属于该属性」的归属校验
 *
 * <p>删除守卫两层都要测到：<b>有可选值</b>时拒绝（避免悬空引用），
 * <b>被 sku_spec_value 引用</b>时拒绝（否则 SKU 的规格展示会缺列）。
 */
@ExtendWith(MockitoExtension.class)
class AttributeServiceImplTest {

    private static final long CATEGORY_ID = 1L;
    private static final long ATTRIBUTE_ID = 11L;
    private static final long VALUE_ID = 101L;

    @Mock
    private ProductAttributeMapper attributeMapper;

    @Mock
    private ProductAttributeValueMapper valueMapper;

    @Mock
    private SkuSpecValueMapper skuSpecValueMapper;

    @Mock
    private ProductCategoryMapper categoryMapper;

    @InjectMocks
    private AttributeServiceImpl attributeService;

    /**
     * 预热 MyBatis-Plus 的 lambda 列缓存
     *
     * <p>纯 Mockito 单测里没有 Spring 容器，{@code TableInfo} 不会随 Mapper 扫描注册；
     * 一旦服务层构造 {@code LambdaQueryWrapper}（尤其 {@code in(...)} 走
     * {@code columnToMapping}），MP 会因为查不到实体缓存而直接抛
     * 「can not find lambda cache for this entity」。这里手工把用到的实体注册一遍。
     */
    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] {
                ProductAttribute.class, ProductAttributeValue.class, SkuSpecValue.class, ProductCategory.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    @Test
    void create_shouldReject_whenCategoryMissing() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.create(attributeRequest("颜色")));
        assertEquals(ResultCode.PRODUCT_CATEGORY_NOT_FOUND.getCode(), ex.getCode());
        verify(attributeMapper, never()).insert(any(ProductAttribute.class));
    }

    @Test
    void create_shouldRejectDuplicateName_withinSameCategory() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category());
        when(attributeMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.create(attributeRequest("颜色")));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_NAME_EXISTS.getCode(), ex.getCode());
        verify(attributeMapper, never()).insert(any(ProductAttribute.class));
    }

    @Test
    void create_shouldDefaultSortAndStatus() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category());
        when(attributeMapper.selectCount(any())).thenReturn(0L);

        attributeService.create(attributeRequest("颜色"));

        ArgumentCaptor<ProductAttribute> captor = ArgumentCaptor.forClass(ProductAttribute.class);
        verify(attributeMapper).insert(captor.capture());
        ProductAttribute saved = captor.getValue();
        assertEquals(CATEGORY_ID, saved.getCategoryId());
        assertEquals("颜色", saved.getName());
        assertEquals(0, saved.getSort());
        assertEquals(1, saved.getStatus());
    }

    @Test
    void delete_shouldReject_whenValuesStillExist() {
        when(attributeMapper.selectById(ATTRIBUTE_ID)).thenReturn(attribute());
        when(valueMapper.selectCount(any())).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.delete(ATTRIBUTE_ID));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_HAS_VALUES.getCode(), ex.getCode());
        verify(attributeMapper, never()).deleteById(ATTRIBUTE_ID);
    }

    @Test
    void delete_shouldReject_whenReferencedBySku() {
        when(attributeMapper.selectById(ATTRIBUTE_ID)).thenReturn(attribute());
        when(valueMapper.selectCount(any())).thenReturn(0L);
        when(skuSpecValueMapper.selectCount(any())).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.delete(ATTRIBUTE_ID));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_IN_USE.getCode(), ex.getCode());
        verify(attributeMapper, never()).deleteById(ATTRIBUTE_ID);
    }

    @Test
    void delete_shouldSucceed_whenClean() {
        when(attributeMapper.selectById(ATTRIBUTE_ID)).thenReturn(attribute());
        when(valueMapper.selectCount(any())).thenReturn(0L);
        when(skuSpecValueMapper.selectCount(any())).thenReturn(0L);

        attributeService.delete(ATTRIBUTE_ID);

        verify(attributeMapper).deleteById(ATTRIBUTE_ID);
    }

    @Test
    void addValue_shouldRejectDuplicateText_withinSameAttribute() {
        when(attributeMapper.selectById(ATTRIBUTE_ID)).thenReturn(attribute());
        when(valueMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.addValue(ATTRIBUTE_ID, valueRequest("黑")));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_VALUE_EXISTS.getCode(), ex.getCode());
        verify(valueMapper, never()).insert(any(ProductAttributeValue.class));
    }

    @Test
    void updateValue_shouldReject_whenValueBelongsToAnotherAttribute() {
        when(attributeMapper.selectById(ATTRIBUTE_ID)).thenReturn(attribute());
        when(valueMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.updateValue(ATTRIBUTE_ID, VALUE_ID, valueRequest("白")));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_VALUE_NOT_FOUND.getCode(), ex.getCode(),
                "归属校验必须落在 SQL 条件里（attribute_id 一起查），不能先查再比");
        verify(valueMapper, never()).updateById(any(ProductAttributeValue.class));
    }

    @Test
    void deleteValue_shouldReject_whenReferencedBySku() {
        when(attributeMapper.selectById(ATTRIBUTE_ID)).thenReturn(attribute());
        when(valueMapper.selectOne(any())).thenReturn(value());
        when(skuSpecValueMapper.selectCount(any())).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> attributeService.deleteValue(ATTRIBUTE_ID, VALUE_ID));
        assertEquals(ResultCode.PRODUCT_ATTRIBUTE_VALUE_IN_USE.getCode(), ex.getCode());
        verify(valueMapper, never()).deleteById(VALUE_ID);
    }

    @Test
    void listByCategory_shouldGroupValuesUnderTheirAttribute() {
        when(attributeMapper.selectList(any())).thenReturn(List.of(attribute(), otherAttribute()));
        when(valueMapper.selectList(any())).thenReturn(List.of(value(), otherValue()));

        List<AttributeVO> result = attributeService.listByCategory(CATEGORY_ID);

        assertEquals(2, result.size());
        AttributeVO color = result.get(0);
        assertEquals(ATTRIBUTE_ID, color.getId());
        assertEquals(1, color.getValues().size(), "只应挂到自己名下的可选值");
        assertEquals("黑", color.getValues().get(0).getAttrValue());

        AttributeVO size = result.get(1);
        assertEquals(1, size.getValues().size());
        assertEquals("L", size.getValues().get(0).getAttrValue());
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private AttributeSaveRequest attributeRequest(String name) {
        AttributeSaveRequest request = new AttributeSaveRequest();
        request.setCategoryId(CATEGORY_ID);
        request.setName(name);
        return request;
    }

    private AttributeValueSaveRequest valueRequest(String text) {
        AttributeValueSaveRequest request = new AttributeValueSaveRequest();
        request.setAttrValue(text);
        return request;
    }

    private ProductCategory category() {
        ProductCategory category = new ProductCategory();
        category.setId(CATEGORY_ID);
        category.setName("数码");
        return category;
    }

    private ProductAttribute attribute() {
        ProductAttribute attribute = new ProductAttribute();
        attribute.setId(ATTRIBUTE_ID);
        attribute.setCategoryId(CATEGORY_ID);
        attribute.setName("颜色");
        attribute.setSort(1);
        attribute.setStatus(1);
        return attribute;
    }

    private ProductAttribute otherAttribute() {
        ProductAttribute attribute = new ProductAttribute();
        attribute.setId(ATTRIBUTE_ID + 1);
        attribute.setCategoryId(CATEGORY_ID);
        attribute.setName("尺寸");
        attribute.setSort(2);
        attribute.setStatus(1);
        return attribute;
    }

    private ProductAttributeValue value() {
        ProductAttributeValue value = new ProductAttributeValue();
        value.setId(VALUE_ID);
        value.setAttributeId(ATTRIBUTE_ID);
        value.setAttrValue("黑");
        value.setSort(1);
        return value;
    }

    private ProductAttributeValue otherValue() {
        ProductAttributeValue value = new ProductAttributeValue();
        value.setId(VALUE_ID + 1);
        value.setAttributeId(ATTRIBUTE_ID + 1);
        value.setAttrValue("L");
        value.setSort(1);
        return value;
    }
}
