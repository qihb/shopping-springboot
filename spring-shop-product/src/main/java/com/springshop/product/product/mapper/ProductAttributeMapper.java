package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.ProductAttribute;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品属性（规格定义）Mapper
 */
@Mapper
public interface ProductAttributeMapper extends BaseMapper<ProductAttribute> {
}
