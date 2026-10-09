package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.SkuSpecValue;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SKU 规格值关联 Mapper
 *
 * <p>纯关联表（无 {@code is_deleted}），按 SKU 维度物理删除重建。
 * 注解 SQL 不经过 MyBatis-Plus 的逻辑删除插件 —— 本表没有该字段，因此无影响。
 */
@Mapper
public interface SkuSpecValueMapper extends BaseMapper<SkuSpecValue> {

    /**
     * 按 SKU 物理删除全部规格关联（保存商品时先删后建）
     */
    @Delete("DELETE FROM sku_spec_value WHERE sku_id = #{skuId}")
    int deleteBySkuId(@Param("skuId") Long skuId);

    /**
     * 批量插入规格关联
     */
    @Insert("<script>"
            + "INSERT INTO sku_spec_value (sku_id, attribute_id, attribute_value_id, create_time) VALUES "
            + "<foreach collection='list' item='item' separator=','>"
            + "(#{item.skuId}, #{item.attributeId}, #{item.attributeValueId}, #{item.createTime})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<SkuSpecValue> list);
}
