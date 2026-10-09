package com.springshop.product.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.product.product.entity.InventoryLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 库存流水 Mapper（append-only：只插入，不更新不删除）
 */
@Mapper
public interface InventoryLogMapper extends BaseMapper<InventoryLog> {

    /**
     * 批量插入流水（批量导入初始化时一次性落账，避免 N 次单条插入）
     */
    @Insert("<script>"
            + "INSERT INTO inventory_log (sku_id, product_id, change_type, "
            + "stock_before, stock_after, locked_before, locked_after, biz_no, operator_id, remark) VALUES "
            + "<foreach collection='list' item='l' separator=','>"
            + "(#{l.skuId}, #{l.productId}, #{l.changeType}, #{l.stockBefore}, #{l.stockAfter}, "
            + "#{l.lockedBefore}, #{l.lockedAfter}, #{l.bizNo}, #{l.operatorId}, #{l.remark})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<InventoryLog> logs);
}
