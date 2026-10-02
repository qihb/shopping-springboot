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

    /**
     * 条件支付：仅当订单仍处于待付款状态时置为已付款（待发货）并记录支付时间
     *
     * <p>与 {@link #cancelIfPendingPayment} 同一并发防护思路：数据库原子条件更新，
     * 影响行数为 0 表示订单已被取消或已被支付，调用方据此放弃（支付模块抛状态非法异常，
     * 超时任务静默跳过），避免支付与取消并发双写。
     */
    @Update("UPDATE orders SET status = #{status}, pay_time = #{payTime}, version = version + 1 "
            + "WHERE order_no = #{orderNo} AND status = #{expectStatus} AND is_deleted = 0")
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("expectStatus") Integer expectStatus,
                 @Param("status") Integer status,
                 @Param("payTime") LocalDateTime payTime);
}
