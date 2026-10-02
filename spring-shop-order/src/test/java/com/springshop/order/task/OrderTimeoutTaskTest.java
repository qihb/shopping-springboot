package com.springshop.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.order.entity.Order;
import com.springshop.order.enums.OrderStatus;
import com.springshop.order.mapper.OrderMapper;
import com.springshop.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单超时自动取消定时任务单元测试（纯 Mockito，不启动 Spring 容器）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderTimeoutTaskTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderTimeoutTask orderTimeoutTask;

    @Test
    void cancelExpiredOrders_should_continue_when_single_order_fails() {
        Order first = order(1000L, "NO1000");
        Order second = order(2000L, "NO2000");
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second));
        // 第一单系统取消抛异常，模拟单笔失败（如库存异常）
        doThrow(new RuntimeException("模拟单笔取消失败")).when(orderService).systemCancel(first);

        orderTimeoutTask.cancelExpiredOrders();

        // 第一单失败不影响第二单仍被处理
        verify(orderService).systemCancel(first);
        verify(orderService).systemCancel(second);
    }

    private Order order(Long id, String orderNo) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        return order;
    }
}
