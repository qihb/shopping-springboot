package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.Inventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 库存 Mapper（单仓，按 sku_id 定位）
 *
 * <p>所有增减都用<b>条件更新</b>：由数据库保证原子性，影响行数为 0 即表示条件不满足
 * （库存不足 / 锁定量不够 / 调整值小于锁定量），由调用方判定并抛业务异常。
 * 这是轻量级的并发防超卖方案，无需分布式锁。
 *
 * <p>⚠️ {@code @Update} 注解 SQL <b>不走 MyBatis-Plus 的逻辑删除插件</b>，
 * 因此每条语句都必须显式写 {@code is_deleted = 0}。
 */
@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * 下单锁定：把 quantity 从「可售」挪到「锁定」
     *
     * <p>条件 {@code stock - locked_stock >= quantity} 即「可售量足够」，0 行 = 库存不足。
     */
    @Update("UPDATE inventory SET locked_stock = locked_stock + #{quantity}, version = version + 1 "
            + "WHERE sku_id = #{skuId} AND stock - locked_stock >= #{quantity} AND is_deleted = 0")
    int lockStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 取消 / 超时释放锁定：把 quantity 从「锁定」挪回「可售」
     *
     * <p>0 行 = 锁定量不足（说明该订单的锁定已被释放，防重复释放）。
     */
    @Update("UPDATE inventory SET locked_stock = locked_stock - #{quantity}, version = version + 1 "
            + "WHERE sku_id = #{skuId} AND locked_stock >= #{quantity} AND is_deleted = 0")
    int releaseLock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 支付出库：锁定转已售 —— 在库实物量与锁定量同时扣减
     *
     * <p>0 行 = 锁定量不足（订单未锁定或已出库，防重复出库）。
     */
    @Update("UPDATE inventory SET stock = stock - #{quantity}, locked_stock = locked_stock - #{quantity}, "
            + "version = version + 1 "
            + "WHERE sku_id = #{skuId} AND locked_stock >= #{quantity} AND is_deleted = 0")
    int outbound(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 后台覆盖在库量（绝对赋值）
     *
     * <p>条件 {@code #{stock} >= locked_stock}：不允许把在库量调到低于已锁定量，
     * 否则会出现「锁定 > 在库」的非法状态。0 行 = 调整值非法。
     */
    @Update("UPDATE inventory SET stock = #{stock}, version = version + 1 "
            + "WHERE sku_id = #{skuId} AND #{stock} >= locked_stock AND is_deleted = 0")
    int adjustStock(@Param("skuId") Long skuId, @Param("stock") Integer stock);

    /**
     * 批量插入库存行（商品创建 / 批量导入初始化用）
     */
    @Insert("<script>"
            + "INSERT INTO inventory (sku_id, stock, locked_stock) VALUES "
            + "<foreach collection='list' item='i' separator=','>"
            + "(#{i.skuId}, #{i.stock}, #{i.lockedStock})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<Inventory> inventories);
}
