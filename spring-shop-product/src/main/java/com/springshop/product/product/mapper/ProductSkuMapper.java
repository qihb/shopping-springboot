package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 SKU Mapper
 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /**
     * 条件扣减库存：影响行数 0 表示库存不足，由调用方判定并抛业务异常
     *
     * <p>单条 UPDATE 由数据库保证原子性，是轻量级的并发扣减方案（无需分布式锁）。
     */
    @Update("UPDATE product_sku SET stock = stock - #{quantity}, version = version + 1 WHERE id = #{skuId} AND stock >= #{quantity}")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 回滚库存（订单取消时调用）
     */
    @Update("UPDATE product_sku SET stock = stock + #{quantity}, version = version + 1 WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
}
