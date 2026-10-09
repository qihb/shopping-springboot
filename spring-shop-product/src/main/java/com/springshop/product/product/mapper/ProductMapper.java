package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.dto.OccupiedProductSpec;
import com.springshop.product.product.entity.Product;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 商品 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 批量插入商品（批量导入用）
     *
     * <p>为什么不用循环单条 insert：一次导入上万行时，单条 insert 会产生上万次网络往返，
     * 是本场景最主要的耗时来源；按批拼成多值 INSERT 后往返次数降到「行数 / 批大小」。
     *
     * <p>{@code useGeneratedKeys} 配 {@code keyProperty = "id"} 能在多值 INSERT 后
     * 把自增主键回填到每个实体上（MyBatis 的 {@code Jdbc3KeyGenerator} 对
     * 「单个 @Param 集合」参数会按元素逐个回填），SKU 才能拿到 productId。
     *
     * <p>create_time / update_time / is_deleted / version 都有库级默认值，不用显式写。
     */
    @Insert("<script>"
            + "INSERT INTO product (category_id, name, subtitle, main_image, sales, status) VALUES "
            + "<foreach collection='list' item='p' separator=','>"
            + "(#{p.categoryId}, #{p.name}, #{p.subtitle}, #{p.mainImage}, #{p.sales}, #{p.status})"
            + "</foreach>"
            + "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBatch(@Param("list") List<Product> products);

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

    /**
     * 批量查询「现有商品的 {@code (productId, 名称, 规格)}」，供商品唯一性查重与导入复用 SPU 使用
     *
     * <p>唯一性单元是 {@code (商品名称, 规格)}（SKU 粒度），横跨 {@code product} 与
     * {@code product_sku} 两张表，所以用一次 join 把「名称 → 已占用的规格集合」整批取回，
     * 由应用层用 {@code SkuSpecNormalizer} 规范化后比对。
     * 分批调用（{@code names} 需分片）而不是按名称逐条查，避免一万个商品一万次往返。
     *
     * <p><b>为什么是 LEFT JOIN</b>：库里存在「SKU 全被逻辑删除、商品本身还在」的行。
     * 若用 INNER JOIN，这类商品在结果里完全消失，调用方会以为该名称没人用 ——
     * 导入就会给它<b>再建一个同名 SPU</b>，正是本次唯一性规则要消灭的现象。
     * LEFT JOIN 让这类商品以 {@code specs = null} 出现：调用方既能据此拿到
     * {@code productId}（复用），又不会把「没有 SKU」误判成「占用了一个空规格」——
     * 所以<b>消费方必须跳过 {@code specs == null} 的行</b>。
     * （{@code s.is_deleted = 0} 写在 ON 而不是 WHERE，否则 LEFT JOIN 会退化成 INNER JOIN）
     *
     * <p>口径是 {@code is_deleted = 0}（只看未删除记录），与
     * {@code ProductSkuMapper#selectOccupiedSkuCodes} 的口径<b>刻意不同</b>：
     * {@code product.name} 没有唯一索引（只有主键与 {@code idx_category_id} / {@code idx_status}），
     * 不存在「查重必须与唯一索引口径一致」这个硬约束，判断依据改为
     * 「运营在商品管理里还能不能看到」—— 已删商品不该继续占名，
     * 这正是「删除后可以重新新增同名同规格」的依据。
     *
     * <p>⚠️ {@code @Select} 注解 SQL <b>不走 MyBatis-Plus 的逻辑删除插件</b>，
     * 两张表的 {@code is_deleted = 0} 都必须显式写出来。
     *
     * @param names 待校验的商品名称集合，调用方需保证非空
     */
    @Select("<script>"
            + "SELECT p.id AS productId, p.name AS name, s.specs AS specs "
            + "FROM product p LEFT JOIN product_sku s ON s.product_id = p.id AND s.is_deleted = 0 "
            + "WHERE p.is_deleted = 0 AND p.name IN "
            + "<foreach collection='names' item='name' open='(' separator=',' close=')'>#{name}</foreach>"
            + "</script>")
    List<OccupiedProductSpec> selectOccupiedProductSpecs(@Param("names") Collection<String> names);
}
