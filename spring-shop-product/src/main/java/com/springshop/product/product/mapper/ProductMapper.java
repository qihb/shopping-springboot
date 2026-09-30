package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 增加销量（下单成功时调用）
     */
    @Update("UPDATE product SET sales = sales + #{quantity}, version = version + 1 WHERE id = #{productId}")
    int increaseSales(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 减少销量（订单取消回滚时调用），用 GREATEST 防止减成负数
     */
    @Update("UPDATE product SET sales = GREATEST(sales - #{quantity}, 0), version = version + 1 WHERE id = #{productId}")
    int decreaseSales(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
