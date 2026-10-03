package com.springshop.stats.service;

import com.springshop.stats.entity.CartRecallProduct;
import com.springshop.stats.entity.CartRecallTarget;
import com.springshop.stats.vo.RecallBuildResultVO;
import com.springshop.stats.vo.RecallSummaryVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 加购未买召回圈人服务
 *
 * <p>本轮只做「圈人」：把「加购了但没买」的人群与商品圈出来落库，不发券、不触达。
 * 先拿到真实规模与触达覆盖率，再决定是否投入券体系与消息通道。
 */
public interface CartRecallService {

    /**
     * 执行圈人：重建待召回人群池 + 生成选品结果
     *
     * @param statDate 统计日期，不可晚于今天
     */
    RecallBuildResultVO build(LocalDate statDate);

    /**
     * 当前统计日期（按配置时区，而非 JVM 默认时区）
     */
    LocalDate currentStatDate();

    /**
     * 待召回池概览
     */
    RecallSummaryVO summary(LocalDate statDate);

    /**
     * 选品结果（按名次升序）
     */
    List<CartRecallProduct> listProducts(LocalDate statDate, Integer limit);

    /**
     * 待召回人群明细
     *
     * @param reachableOnly 仅返回可触达（有手机号或 openid）的条目
     */
    List<CartRecallTarget> listTargets(LocalDate statDate, Integer limit, boolean reachableOnly);
}
