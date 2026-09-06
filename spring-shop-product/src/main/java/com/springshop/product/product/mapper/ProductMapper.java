package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
