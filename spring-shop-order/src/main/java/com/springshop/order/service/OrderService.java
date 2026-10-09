package com.springshop.order.service;

import com.springshop.common.result.PageResult;
import com.springshop.order.dto.AdminOrderPageQuery;
import com.springshop.order.dto.OrderCreateRequest;
import com.springshop.order.dto.OrderExportQuery;
import com.springshop.order.dto.OrderPageQuery;
import com.springshop.order.entity.Order;
import com.springshop.order.vo.OrderVO;

import java.util.List;

/**
 * 订单服务
 *
 * <p>前台方法均以当前登录用户为边界，越权访问一律视为订单不存在。
 */
public interface OrderService {

    /**
     * 下单：来源为购物车勾选项，返回订单号
     *
     * <p>库存只做<b>锁定</b>（可售 → 锁定），不动在库量；真正的出库发生在支付成功时。
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
     * 按订单号查询订单实体（跨模块只读，供支付模块校验使用），不存在返回 null
     */
    Order getByOrderNo(String orderNo);

    /**
     * 模拟支付：待付款 → 待发货，并在同一事务内<b>出库</b>（在库量与锁定量同时扣减）
     *
     * <p>走条件更新，因此并发重复支付只有一方成功，不会把库存扣两次。
     */
    void pay(Long userId, String orderNo);

    /**
     * 标记订单已支付（跨模块，供支付模块在事务内调用）：仅当订单仍为待付款时
     * 置为待发货并记录支付时间，条件更新防并发；返回是否更新成功（false 表示
     * 订单已被取消或已支付，调用方据此回滚整个支付事务）
     *
     * <p>成功时会在同一事务内出库；出库失败抛异常，连状态变更一起回滚。
     */
    boolean markPaid(String orderNo);

    /**
     * 取消订单：待付款 → 已取消，释放库存锁定（锁定 → 可售）
     */
    void cancel(Long userId, String orderNo);

    /**
     * 系统取消（超时自动取消定时任务调用）：仅当订单仍为待付款时置为已取消并释放库存锁定；
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
     * 后台订单导出取数（分页拉取，供异步导出任务边查边写）
     *
     * <p>与 {@link #pageForAdmin} 共用同一套筛选口径，区别只在「不算总数」：
     * 导出不展示总页数，每页多一次 COUNT 纯属浪费。
     *
     * @param query    导出条件，传 {@code ids} 时只取选中的订单
     * @param lastId   keyset 游标：上一页最后一条的 id，{@code null} 表示从第一页开始。
     *                 按 id 倒序取 {@code id < lastId}。不能用 OFFSET 页码——
     *                 导出要跑几分钟，期间有人下单会让后续页窗口整体后移，
     *                 导致已导出的行重复、边缘的行被整页跳过
     * @param pageSize 每页条数
     */
    List<OrderVO> exportPage(OrderExportQuery query, Long lastId, long pageSize);

    /**
     * 后台发货：待发货 → 待收货
     */
    void ship(String orderNo);
}
