package com.springshop.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.stats.entity.CartRecallProduct;
import org.apache.ibatis.annotations.Mapper;

/**
 * 加购未买选品结果 Mapper
 */
@Mapper
public interface CartRecallProductMapper extends BaseMapper<CartRecallProduct> {
}
