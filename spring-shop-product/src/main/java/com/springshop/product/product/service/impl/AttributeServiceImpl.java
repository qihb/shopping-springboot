package com.springshop.product.product.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.springshop.product.product.service.AttributeService;
import com.springshop.product.product.vo.AttributeVO;
import com.springshop.product.product.vo.AttributeValueVO;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品属性（规格体系）服务实现
 *
 * <p>删除守卫分两层，缺一不可：
 * <ol>
 *   <li>属性有可选值 → 先删可选值（避免产生「值已不存在但 SKU 还指着它」的悬空引用）；</li>
 *   <li>属性 / 可选值已被 {@code sku_spec_value} 引用 → 拒绝删除（否则 SKU 的规格展示会缺列）。</li>
 * </ol>
 */
@Service
public class AttributeServiceImpl implements AttributeService {

    private final ProductAttributeMapper attributeMapper;
    private final ProductAttributeValueMapper valueMapper;
    private final SkuSpecValueMapper skuSpecValueMapper;
    private final ProductCategoryMapper categoryMapper;

    public AttributeServiceImpl(ProductAttributeMapper attributeMapper,
                                ProductAttributeValueMapper valueMapper,
                                SkuSpecValueMapper skuSpecValueMapper,
                                ProductCategoryMapper categoryMapper) {
        this.attributeMapper = attributeMapper;
        this.valueMapper = valueMapper;
        this.skuSpecValueMapper = skuSpecValueMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public List<AttributeVO> listByCategory(Long categoryId) {
        List<ProductAttribute> attributes = attributeMapper.selectList(
                Wrappers.<ProductAttribute>lambdaQuery()
                        .eq(ProductAttribute::getCategoryId, categoryId)
                        .orderByAsc(ProductAttribute::getSort)
                        .orderByAsc(ProductAttribute::getId));
        if (attributes.isEmpty()) {
            return List.of();
        }

        List<Long> attributeIds = attributes.stream().map(ProductAttribute::getId).toList();
        Map<Long, List<AttributeValueVO>> valueMap = valueMapper.selectList(
                        Wrappers.<ProductAttributeValue>lambdaQuery()
                                .in(ProductAttributeValue::getAttributeId, attributeIds)
                                .orderByAsc(ProductAttributeValue::getSort)
                                .orderByAsc(ProductAttributeValue::getId))
                .stream()
                .map(this::toValueVO)
                .collect(Collectors.groupingBy(AttributeValueVO::getAttributeId));

        return attributes.stream()
                .map(attribute -> toVO(attribute, valueMap.getOrDefault(attribute.getId(), List.of())))
                .collect(Collectors.toList());
    }

    @Override
    public AttributeVO getById(Long id) {
        ProductAttribute attribute = requireAttribute(id);
        List<AttributeValueVO> values = valueMapper.selectList(
                        Wrappers.<ProductAttributeValue>lambdaQuery()
                                .eq(ProductAttributeValue::getAttributeId, id)
                                .orderByAsc(ProductAttributeValue::getSort)
                                .orderByAsc(ProductAttributeValue::getId))
                .stream().map(this::toValueVO).collect(Collectors.toList());
        return toVO(attribute, values);
    }

    @Override
    public void create(AttributeSaveRequest request) {
        requireCategory(request.getCategoryId());
        String name = request.getName().trim();
        ensureAttributeNameAvailable(request.getCategoryId(), name, null);

        ProductAttribute attribute = new ProductAttribute();
        attribute.setCategoryId(request.getCategoryId());
        attribute.setName(name);
        attribute.setSort(request.getSort() == null ? 0 : request.getSort());
        attribute.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        attributeMapper.insert(attribute);
    }

    @Override
    public void update(Long id, AttributeSaveRequest request) {
        ProductAttribute attribute = requireAttribute(id);
        requireCategory(request.getCategoryId());
        String name = request.getName().trim();
        ensureAttributeNameAvailable(request.getCategoryId(), name, id);

        attribute.setCategoryId(request.getCategoryId());
        attribute.setName(name);
        if (request.getSort() != null) {
            attribute.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            attribute.setStatus(request.getStatus());
        }
        attributeMapper.updateById(attribute);
    }

    @Override
    public void delete(Long id) {
        requireAttribute(id);

        Long valueCount = valueMapper.selectCount(
                Wrappers.<ProductAttributeValue>lambdaQuery()
                        .eq(ProductAttributeValue::getAttributeId, id));
        if (valueCount != null && valueCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_ATTRIBUTE_HAS_VALUES);
        }

        Long refCount = skuSpecValueMapper.selectCount(
                Wrappers.<SkuSpecValue>lambdaQuery().eq(SkuSpecValue::getAttributeId, id));
        if (refCount != null && refCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_ATTRIBUTE_IN_USE);
        }

        attributeMapper.deleteById(id);
    }

    @Override
    public AttributeValueVO addValue(Long attributeId, AttributeValueSaveRequest request) {
        requireAttribute(attributeId);
        String text = request.getAttrValue().trim();
        ensureValueTextAvailable(attributeId, text, null);

        ProductAttributeValue value = new ProductAttributeValue();
        value.setAttributeId(attributeId);
        value.setAttrValue(text);
        value.setSort(request.getSort() == null ? 0 : request.getSort());
        valueMapper.insert(value);
        return toValueVO(value);
    }

    @Override
    public void updateValue(Long attributeId, Long valueId, AttributeValueSaveRequest request) {
        requireAttribute(attributeId);
        ProductAttributeValue value = requireValue(attributeId, valueId);
        String text = request.getAttrValue().trim();
        ensureValueTextAvailable(attributeId, text, valueId);

        value.setAttrValue(text);
        if (request.getSort() != null) {
            value.setSort(request.getSort());
        }
        valueMapper.updateById(value);
    }

    @Override
    public void deleteValue(Long attributeId, Long valueId) {
        requireAttribute(attributeId);
        requireValue(attributeId, valueId);

        Long refCount = skuSpecValueMapper.selectCount(
                Wrappers.<SkuSpecValue>lambdaQuery().eq(SkuSpecValue::getAttributeValueId, valueId));
        if (refCount != null && refCount > 0) {
            throw new BusinessException(ResultCode.PRODUCT_ATTRIBUTE_VALUE_IN_USE);
        }

        valueMapper.deleteById(valueId);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private ProductAttribute requireAttribute(Long id) {
        ProductAttribute attribute = attributeMapper.selectById(id);
        if (attribute == null) {
            throw new BusinessException(ResultCode.PRODUCT_ATTRIBUTE_NOT_FOUND);
        }
        return attribute;
    }

    /** 可选值必须属于该属性，避免拿 A 属性的 id 去改 B 属性的值 */
    private ProductAttributeValue requireValue(Long attributeId, Long valueId) {
        ProductAttributeValue value = valueMapper.selectOne(
                Wrappers.<ProductAttributeValue>lambdaQuery()
                        .eq(ProductAttributeValue::getId, valueId)
                        .eq(ProductAttributeValue::getAttributeId, attributeId));
        if (value == null) {
            throw new BusinessException(ResultCode.PRODUCT_ATTRIBUTE_VALUE_NOT_FOUND);
        }
        return value;
    }

    private void requireCategory(Long categoryId) {
        ProductCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(ResultCode.PRODUCT_CATEGORY_NOT_FOUND);
        }
    }

    private void ensureAttributeNameAvailable(Long categoryId, String name, Long excludeId) {
        Long count = attributeMapper.selectCount(Wrappers.<ProductAttribute>lambdaQuery()
                .eq(ProductAttribute::getCategoryId, categoryId)
                .eq(ProductAttribute::getName, name)
                .ne(excludeId != null, ProductAttribute::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.PRODUCT_ATTRIBUTE_NAME_EXISTS);
        }
    }

    private void ensureValueTextAvailable(Long attributeId, String text, Long excludeId) {
        Long count = valueMapper.selectCount(Wrappers.<ProductAttributeValue>lambdaQuery()
                .eq(ProductAttributeValue::getAttributeId, attributeId)
                .eq(ProductAttributeValue::getAttrValue, text)
                .ne(excludeId != null, ProductAttributeValue::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.PRODUCT_ATTRIBUTE_VALUE_EXISTS);
        }
    }

    private AttributeVO toVO(ProductAttribute attribute, List<AttributeValueVO> values) {
        AttributeVO vo = new AttributeVO();
        vo.setId(attribute.getId());
        vo.setCategoryId(attribute.getCategoryId());
        vo.setName(attribute.getName());
        vo.setSort(attribute.getSort());
        vo.setStatus(attribute.getStatus());
        vo.setValues(values.stream()
                .sorted(Comparator.comparing(AttributeValueVO::getSort,
                        Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.toList()));
        return vo;
    }

    private AttributeValueVO toValueVO(ProductAttributeValue value) {
        AttributeValueVO vo = new AttributeValueVO();
        vo.setId(value.getId());
        vo.setAttributeId(value.getAttributeId());
        vo.setAttrValue(value.getAttrValue());
        vo.setSort(value.getSort());
        return vo;
    }
}
