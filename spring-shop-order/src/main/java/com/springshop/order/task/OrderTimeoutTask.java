package com.springshop.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.order.entity.Order;
import com.springshop.order.enums.OrderStatus;
import com.springshop.order.mapper.OrderMapper;
import com.springshop.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单超时自动取消定时任务
 *
 * <p>周期扫描「待付款且创建时间早于超时线」的订单，逐单走系统取消
 * （条件更新置为已取消 + 回滚库存/销量），单笔失败仅记录日志，不影响同批其余订单。
 */
@Component
public class OrderTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutTask.class);

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 待付款超时分钟数，超时即自动取消 */
    @Value("${order.timeout.cancel-minutes:30}")
    private long cancelMinutes;

    public OrderTimeoutTask(OrderMapper orderMapper, OrderService orderService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    /**
     * 每 60 秒执行一次：查询超时未支付的待付款订单并逐单系统取消
     */
    @Scheduled(fixedDelay = 60_000)
    public void cancelExpiredOrders() {
        // 超时线 = 当前时间 - 配置分钟数，只处理创建时间早于超时线的待付款单
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(cancelMinutes);
        List<Order> expiredOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.PENDING_PAYMENT.getCode())
                .lt(Order::getCreateTime, deadline));
        if (expiredOrders.isEmpty()) {
            return;
        }
        for (Order order : expiredOrders) {
            // 逐单兜底：单笔失败（如库存异常）只记日志，继续处理下一单
            try {
                orderService.systemCancel(order);
            } catch (Exception e) {
                log.error("订单超时自动取消失败, orderNo={}", order.getOrderNo(), e);
            }
        }
    }
}
