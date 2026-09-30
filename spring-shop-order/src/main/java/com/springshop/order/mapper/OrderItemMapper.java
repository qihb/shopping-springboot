package com.springshop.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.order.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单明细 Mapper
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
