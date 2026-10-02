package com.springshop.pay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.order.entity.Order;
import com.springshop.order.enums.OrderStatus;
import com.springshop.order.service.OrderService;
import com.springshop.pay.entity.PayRecord;
import com.springshop.pay.mapper.PayRecordMapper;
import com.springshop.pay.service.PayService;
import com.springshop.pay.vo.PayResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 支付服务实现（模拟支付网关）
 *
 * <p>真实接入第三方支付时，仅需把「标记支付成功」替换为网关回调驱动，
 * 幂等与并发防护设计保持不变。
 */
@Service
public class PayServiceImpl implements PayService {

    /** 支付状态：支付成功（模拟网关一次到位，与 pay_record.status 字段对应） */
    private static final int STATUS_SUCCESS = 1;

    private final PayRecordMapper payRecordMapper;
    private final OrderService orderService;

    public PayServiceImpl(PayRecordMapper payRecordMapper, OrderService orderService) {
        this.payRecordMapper = payRecordMapper;
        this.orderService = orderService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultVO mockPay(Long userId, String orderNo) {
        // 校验订单：存在（6001）→ 归属当前用户（6002）。
        // 归属校验必须先于幂等查询：防止越权用户借幂等分支读取他人订单的支付结果
        Order order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.PAY_ORDER_NOT_FOUND);
        }
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ResultCode.PAY_FORBIDDEN);
        }

        // 幂等：已存在支付记录（模拟网关重复回调/用户重复点击）时直接返回既有结果，
        // 不再触碰订单（订单状态已由首次支付推进）
        PayRecord existing = payRecordMapper.selectOne(new LambdaQueryWrapper<PayRecord>()
                .eq(PayRecord::getOrderNo, orderNo));
        if (existing != null) {
            return toVO(existing);
        }

        // 校验状态：仍为待付款（6003）
        if (!OrderStatus.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PAY_STATUS_ILLEGAL);
        }

        // 顺序说明：先 markPaid（条件更新，仅 status=1 生效）再写支付流水。
        // markPaid 是并发串行化点：并发双击同一订单时，后到方的条件更新影响 0 行直接
        // 抛 6003 整体回滚，根本不会插入重复流水；若先插流水，则要靠 pay_record 的
        // 唯一键兜底，报错语义不友好。与超时取消任务也天然互斥（同为 status=1 条件更新）。
        // 两步同一事务，markPaid 失败即抛异常回滚，不存在「改了状态没流水」的中间态。
        if (!orderService.markPaid(orderNo)) {
            throw new BusinessException(ResultCode.PAY_STATUS_ILLEGAL);
        }

        // 支付流水金额取订单快照 pay_amount，绝不信任前端传入
        PayRecord record = new PayRecord();
        record.setOrderNo(orderNo);
        record.setUserId(userId);
        record.setAmount(order.getPayAmount());
        record.setStatus(STATUS_SUCCESS);
        record.setPayTime(LocalDateTime.now());
        payRecordMapper.insert(record);

        return toVO(record);
    }

    private PayResultVO toVO(PayRecord record) {
        PayResultVO vo = new PayResultVO();
        vo.setOrderNo(record.getOrderNo());
        vo.setAmount(record.getAmount());
        vo.setStatus(record.getStatus());
        vo.setPayTime(record.getPayTime());
        return vo;
    }
}
