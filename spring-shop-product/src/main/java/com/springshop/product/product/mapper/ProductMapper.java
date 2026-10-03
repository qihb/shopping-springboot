package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.Product;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}
