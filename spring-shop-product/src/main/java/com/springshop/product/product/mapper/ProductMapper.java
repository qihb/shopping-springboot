package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
     * 批量查询已存在的商品名称（<b>只看未删除的商品</b>），供批量导入重名校验使用
     *
     * <p>与 {@code ProductSkuMapper#selectOccupiedSkuCodes} 的口径<b>刻意不同</b>，这里是
     * {@code is_deleted = 0}。原因：{@code product.name} <b>没有唯一索引</b>（只有主键与
     * {@code idx_category_id} / {@code idx_status}），所以不存在「查重必须与唯一索引口径一致」
     * 这个硬约束。判断依据因此改为「运营在商品管理里还能不能看到同名商品」——
     * 已经删掉的商品不该再挡住同名商品重新导入。
     *
     * <p>注意：{@code @Select} 注解 SQL 不会经过 MyBatis-Plus 的逻辑删除插件，
     * {@code is_deleted = 0} 必须显式写出来。
     *
     * @param names 待校验的商品名称集合，调用方需保证非空
     */
    @Select("<script>"
            + "SELECT name FROM product WHERE is_deleted = 0 AND name IN "
            + "<foreach collection='names' item='name' open='(' separator=',' close=')'>#{name}</foreach>"
            + "</script>")
    List<String> selectOccupiedProductNames(@Param("names") Collection<String> names);
}
