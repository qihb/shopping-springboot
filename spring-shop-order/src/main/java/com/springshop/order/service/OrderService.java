package com.springshop.order.service;

import com.springshop.common.result.PageResult;
import com.springshop.order.dto.AdminOrderPageQuery;
import com.springshop.order.dto.OrderCreateRequest;
import com.springshop.order.dto.OrderPageQuery;
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
