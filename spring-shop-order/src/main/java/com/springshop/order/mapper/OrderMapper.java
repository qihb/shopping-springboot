package com.springshop.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.springshop.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 订单 Mapper
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 条件取消订单：仅当订单仍处于待付款状态时置为已取消并记录取消时间
     *
     * <p>影响 0 行表示订单已被用户取消或已支付，由调用方静默跳过，
     * 以数据库原子条件更新避免超时任务与用户操作并发双写。update_time 由数据库自动维护。
     */
    @Update("UPDATE orders SET status = #{status}, cancel_time = #{cancelTime}, version = version + 1 "
            + "WHERE id = #{id} AND status = #{expectStatus} AND is_deleted = 0")
    int cancelIfPendingPayment(@Param("id") Long id,
                               @Param("expectStatus") Integer expectStatus,
                               @Param("status") Integer status,
                               @Param("cancelTime") LocalDateTime cancelTime);
}
