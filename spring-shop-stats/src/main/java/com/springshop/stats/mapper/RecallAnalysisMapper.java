package com.springshop.stats.mapper;

import com.springshop.stats.entity.CartRecallProduct;
import com.springshop.stats.vo.RecallCandidateVO;
import com.springshop.stats.vo.RecallSummaryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 召回圈人分析 Mapper（复杂聚合 SQL，走 XML）
 *
 * <p>聚合一律在数据库完成，不把明细拉到 JVM 聚合（千万行会 OOM）。
 * 待召回人群用「按 cart_item.id 键集分页」分批拉取，避免一次性装载全量导致内存膨胀，
 * 同时避免为了流式读取而开长事务。
 *
 * <p>时间窗口统一由调用方算好 {@code idleBefore} / {@code idleAfter} 传入，
 * SQL 内不做日期函数运算 —— 既避开 MySQL 与 H2 的函数差异，也让执行计划稳定。
 */
@Mapper
public interface RecallAnalysisMapper {

    /**
     * 分批拉取「加购未买」待召回条目（键集分页，按 cart_item.id 升序）
     *
     * <p>口径要点：
     * <ul>
     *   <li>{@code create_time} 是「首次加购时刻」：加购时写入，后续改数量/改勾选只刷新 update_time，
     *       因此用它做时间窗口过滤是准确的；</li>
     *   <li>只统计有效条目（SKU 与所属商品均未删除且在售、账号正常），与购物车列表口径一致，
     *       否则会圈到已下架商品或已禁用账号；</li>
     *   <li>按 cart_item.id 键集分页而非 OFFSET 分页：跑批过程中购物车持续写入，
     *       OFFSET 会因插入而漂移、导致漏行或重复。</li>
     * </ul>
     *
     * @param idleBefore 加购时间上界（= 跑批时刻 - 最小闲置小时数），早于它才算「闲置久了」
     * @param idleAfter  加购时间下界（= 跑批时刻 - 最大闲置天数），早于它的算僵尸购物车、不再召回
     */
    List<RecallCandidateVO> selectAbandonTargetsPage(@Param("idleBefore") LocalDateTime idleBefore,
                                                     @Param("idleAfter") LocalDateTime idleAfter,
                                                     @Param("minQuantity") int minQuantity,
                                                     @Param("lastId") long lastId,
                                                     @Param("batchSize") int batchSize);

    /**
     * 按商品聚合「加购未买」指标，并左连「近 N 天已成交用户数」，用于计算弃购率
     *
     * <p><b>为什么从 {@code cart_recall_target}（人群池）聚合，而不是再查一次 {@code cart_item}</b>：
     * 人群池已经完成全部过滤（有效商品、闲置窗口、异常账号剔除），从池子聚合可以保证
     * 「选品结果」与「待召回人群」永远来自同一批数据。此前从 {@code cart_item} 重查时，
     * 商品聚合漏了异常账号剔除，结果羊毛账号把长尾商品刷进了弃购率榜 —— 同一套过滤规则
     * 写两遍就是这种 bug 的温床。
     *
     * <p>件数与金额按单用户封顶（{@code LEAST(quantity, qtyCap)}）计算，防止单个用户
     * 用超大数量把某个商品顶到榜首。
     */
    List<CartRecallProduct> selectProductAgg(@Param("statDate") LocalDate statDate,
                                             @Param("qtyCap") int qtyCap,
                                             @Param("paidFrom") LocalDateTime paidFrom);

    /**
     * 购物车条目数超过阈值的账号（羊毛党/爬虫特征），由调用方在内存中剔除，
     * 避免在主查询里挂关联子查询反复全表扫描
     */
    List<Long> selectAbnormalUserIds(@Param("maxCartSize") int maxCartSize);

    /**
     * 待召回池概览（供后台与运营评估规模与触达覆盖率）
     */
    RecallSummaryVO selectSummary(@Param("statDate") LocalDate statDate);
}
