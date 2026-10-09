package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.ProductAttributeValue;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品属性可选值 Mapper
 */
@Mapper
public interface ProductAttributeValueMapper extends BaseMapper<ProductAttributeValue> {
}
