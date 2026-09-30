package com.springshop.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.order.entity.ShippingAddress;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址 Mapper
 */
@Mapper
public interface ShippingAddressMapper extends BaseMapper<ShippingAddress> {
}
