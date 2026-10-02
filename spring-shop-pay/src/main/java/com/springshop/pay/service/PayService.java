package com.springshop.pay.service;

import com.springshop.pay.vo.PayResultVO;

/**
 * 支付服务
 */
public interface PayService {

    /**
     * 模拟支付：校验订单存在、归属当前用户且处于待付款状态后，
     * 推进订单状态并写入支付流水，返回支付结果。
     *
     * <p>幂等：同一订单已存在支付记录时直接返回既有结果，不再触碰订单；
     * 并发安全：订单状态推进使用数据库条件更新，与订单超时取消任务天然互斥。
     */
    PayResultVO mockPay(Long userId, String orderNo);
}
