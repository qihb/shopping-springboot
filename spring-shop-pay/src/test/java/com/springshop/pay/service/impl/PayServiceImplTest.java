package com.springshop.pay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.order.entity.Order;
import com.springshop.order.enums.OrderStatus;
import com.springshop.order.service.OrderService;
import com.springshop.pay.entity.PayRecord;
import com.springshop.pay.mapper.PayRecordMapper;
import com.springshop.pay.vo.PayResultVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付服务单元测试（纯 Mockito，不启动 Spring 容器）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final String ORDER_NO = "NO1000";

    @Mock
    private PayRecordMapper payRecordMapper;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private PayServiceImpl payService;

    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        // 预热 Lambda 列名缓存，避免单测环境无 MP 全量初始化时 LambdaQueryWrapper 解析失败
        try {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), PayRecord.class);
        } catch (Exception ignore) {
            // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
        }
    }

    // ---------------- 支付成功 ----------------

    @Test
    void mockPay_should_insert_record_and_mark_order_paid() {
        when(payRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order(USER_ID, OrderStatus.PENDING_PAYMENT.getCode()));
        when(orderService.markPaid(ORDER_NO)).thenReturn(true);

        PayResultVO vo = payService.mockPay(USER_ID, ORDER_NO);

        // 写支付流水：金额取订单快照 pay_amount，状态为支付成功
        ArgumentCaptor<PayRecord> captor = ArgumentCaptor.forClass(PayRecord.class);
        verify(payRecordMapper).insert(captor.capture());
        PayRecord record = captor.getValue();
        assertEquals(ORDER_NO, record.getOrderNo());
        assertEquals(USER_ID, record.getUserId());
        assertEquals(0, new BigDecimal("20.00").compareTo(record.getAmount()));
        assertEquals(1, record.getStatus());
        assertNotNull(record.getPayTime());
        // 推进订单状态：待付款 → 待发货
        verify(orderService).markPaid(ORDER_NO);
        assertEquals(ORDER_NO, vo.getOrderNo());
        assertEquals(1, vo.getStatus());
        assertNotNull(vo.getPayTime());
    }

    // ---------------- 校验失败 ----------------

    @Test
    void mockPay_should_fail_when_order_not_found() {
        when(payRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(orderService.getByOrderNo(ORDER_NO)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.mockPay(USER_ID, ORDER_NO));
        assertEquals(ResultCode.PAY_ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(orderService, never()).markPaid(any());
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    @Test
    void mockPay_should_fail_when_order_not_owned() {
        when(payRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // 订单属于其他用户（999）
        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order(999L, OrderStatus.PENDING_PAYMENT.getCode()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.mockPay(USER_ID, ORDER_NO));
        assertEquals(ResultCode.PAY_FORBIDDEN.getCode(), ex.getCode());
        verify(orderService, never()).markPaid(any());
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    @Test
    void mockPay_should_fail_when_status_not_pending_payment() {
        when(payRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // 订单已支付（待发货），不可重复走支付校验
        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order(USER_ID, OrderStatus.PENDING_SHIPMENT.getCode()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.mockPay(USER_ID, ORDER_NO));
        assertEquals(ResultCode.PAY_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(orderService, never()).markPaid(any());
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    // ---------------- 幂等 ----------------

    @Test
    void mockPay_should_return_directly_when_record_exists() {
        // 重复支付（订单已推进为待发货）：归属校验通过后，已存在成功支付记录直接返回，
        // 不再推进状态、不再写流水
        PayRecord existing = new PayRecord();
        existing.setOrderNo(ORDER_NO);
        existing.setUserId(USER_ID);
        existing.setAmount(new BigDecimal("20.00"));
        existing.setStatus(1);
        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order(USER_ID, OrderStatus.PENDING_SHIPMENT.getCode()));
        when(payRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        PayResultVO vo = payService.mockPay(USER_ID, ORDER_NO);

        verify(orderService, never()).markPaid(any());
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
        assertEquals(ORDER_NO, vo.getOrderNo());
        assertEquals(1, vo.getStatus());
    }

    @Test
    void mockPay_should_fail_when_pay_others_paid_order() {
        // 他人已支付的订单：归属校验必须先于幂等返回，
        // 防止越权用户借幂等分支读取他人订单的支付结果
        PayRecord existing = new PayRecord();
        existing.setOrderNo(ORDER_NO);
        existing.setUserId(999L);
        existing.setAmount(new BigDecimal("20.00"));
        existing.setStatus(1);
        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order(999L, OrderStatus.PENDING_SHIPMENT.getCode()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.mockPay(USER_ID, ORDER_NO));
        assertEquals(ResultCode.PAY_FORBIDDEN.getCode(), ex.getCode());
        // 归属校验在前：根本不应触碰支付记录查询
        verify(payRecordMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(orderService, never()).markPaid(any());
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    // ---------------- 并发落败（条件更新 0 行） ----------------

    @Test
    void mockPay_should_fail_when_mark_paid_misses() {
        when(payRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(orderService.getByOrderNo(ORDER_NO))
                .thenReturn(order(USER_ID, OrderStatus.PENDING_PAYMENT.getCode()));
        // 并发下订单已被取消/已支付：条件更新影响 0 行
        when(orderService.markPaid(ORDER_NO)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> payService.mockPay(USER_ID, ORDER_NO));
        assertEquals(ResultCode.PAY_STATUS_ILLEGAL.getCode(), ex.getCode());
        // 先 markPaid 后写流水：并发落败方根本不会插入流水（事务内也无需回滚兜底）
        verify(payRecordMapper, never()).insert(any(PayRecord.class));
    }

    // ---------------- 构造辅助 ----------------

    private Order order(Long userId, Integer status) {
        Order order = new Order();
        order.setId(1000L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(userId);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("20.00"));
        order.setPayAmount(new BigDecimal("20.00"));
        return order;
    }
}
