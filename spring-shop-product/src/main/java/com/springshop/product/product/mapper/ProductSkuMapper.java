package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

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

    /**
     * 批量查询已被占用的 SKU 编码（<b>忽略逻辑删除</b>）
     *
     * <p>为什么不能用 MyBatis-Plus 的 lambda 查询：{@code uk_sku_code} 是建立在 {@code sku_code}
     * 上的唯一索引，<b>不区分 {@code is_deleted}</b>——被逻辑删除的 SKU 依然占着编码。
     * 若查重时自动过滤掉已删除行，校验会放行、INSERT 阶段才撞唯一键并抛 500。
     * 因此批量导入的编码查重必须与唯一索引口径一致。
     *
     * @param skuCodes 待校验的 SKU 编码集合，调用方需保证非空
     */
    @Select("<script>"
            + "SELECT sku_code FROM product_sku WHERE sku_code IN "
            + "<foreach collection='skuCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach>"
            + "</script>")
    List<String> selectOccupiedSkuCodes(@Param("skuCodes") Collection<String> skuCodes);
}
