package com.springshop.order.service;

import com.springshop.common.result.PageResult;
import com.springshop.order.dto.AdminOrderPageQuery;
import com.springshop.order.dto.OrderCreateRequest;
import com.springshop.order.dto.OrderPageQuery;
import com.springshop.order.entity.Order;
import com.springshop.order.vo.OrderVO;

/**
 * 订单服务
 *
 * <p>前台方法均以当前登录用户为边界，越权访问一律视为订单不存在。
 */
public interface OrderService {

    /**
     * 下单：来源为购物车勾选项，返回订单号
     */
    String create(Long userId, OrderCreateRequest request);

    /**
     * 我的订单分页
     */
    PageResult<OrderVO> pageMine(Long userId, OrderPageQuery query);

    /**
     * 订单详情（越权视为不存在）
     */
    OrderVO detail(Long userId, String orderNo);

    /**
     * 模拟支付：待付款 → 待发货
     */
    void pay(Long userId, String orderNo);

    /**
     * 取消订单：待付款 → 已取消，回滚库存
     */
    void cancel(Long userId, String orderNo);

    /**
     * 系统取消（超时自动取消定时任务调用）：仅当订单仍为待付款时置为已取消并回滚库存；
     * 订单已被用户取消或已支付时静默返回，不抛异常
     */
    void systemCancel(Order order);

    /**
     * 确认收货：待收货 → 已完成
     */
    void confirm(Long userId, String orderNo);

    /**
     * 后台订单分页
     */
    PageResult<OrderVO> pageForAdmin(AdminOrderPageQuery query);

    /**
     * 后台发货：待发货 → 待收货
     */
    void ship(String orderNo);
}
