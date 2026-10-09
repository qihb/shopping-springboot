package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.ProductSku;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 商品 SKU Mapper
 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /**
     * 批量插入 SKU（批量导入用），调用前需保证每个 SKU 的 productId 已回填
     *
     * <p>⚠️ 自定义 {@code @Insert}，<b>不会回填自增主键</b>。调用方若需要新 SKU 的 id
     * （例如建 {@code inventory} 库存行），得按 {@code sku_code} 回查一次。
     *
     * <p>注意这里<b>没有 {@code stock}</b>：库存自 V9 起归 {@code inventory} 表，
     * V10 已删除镜像列 {@code product_sku.stock}，调用方要另外调 {@code InventoryService.initStock}。
     */
    @Insert("<script>"
            + "INSERT INTO product_sku (product_id, sku_code, specs, price, original_price, status) VALUES "
            + "<foreach collection='list' item='s' separator=','>"
            + "(#{s.productId}, #{s.skuCode}, #{s.specs}, #{s.price}, #{s.originalPrice}, #{s.status})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<ProductSku> skus);

    // 原 deductStock / restoreStock 已删除（2026-10-09，V9 数据模型对齐）。
    // 它们操作的是 product_sku.stock —— 该列已由 V10 删除，库存流转统一走 InventoryService：
    // 下单 lock / 支付 outbound / 取消 release。

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
