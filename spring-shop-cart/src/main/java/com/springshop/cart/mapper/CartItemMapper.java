package com.springshop.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.cart.entity.CartItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 购物车条目 Mapper
 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
}
