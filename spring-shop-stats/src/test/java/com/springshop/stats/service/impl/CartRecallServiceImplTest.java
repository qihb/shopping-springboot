package com.springshop.stats.service.impl;

import com.springshop.common.exception.BusinessException;
import com.springshop.stats.config.RecallProperties;
import com.springshop.stats.entity.CartRecallProduct;
import com.springshop.stats.entity.CartRecallTarget;
import com.springshop.stats.mapper.CartRecallProductMapper;
import com.springshop.stats.mapper.CartRecallTargetMapper;
import com.springshop.stats.mapper.RecallAnalysisMapper;
import com.springshop.stats.vo.RecallBuildResultVO;
import com.springshop.stats.vo.RecallCandidateVO;
import com.springshop.stats.vo.RecallSummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 加购未买召回圈人服务单元测试（纯 Mockito，不启动 Spring 容器）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartRecallServiceImplTest {

    @Mock
    private RecallAnalysisMapper analysisMapper;

    @Mock
    private CartRecallTargetMapper targetMapper;

    @Mock
    private CartRecallProductMapper productMapper;

    private RecallProperties props;

    private CartRecallServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new RecallProperties();
        props.setTopN(100);
        props.setMinAbandonUsers(3);
        props.setPageSize(2000);
        props.setWriteBatchSize(500);
        service = new CartRecallServiceImpl(analysisMapper, targetMapper, productMapper, props);

        // 默认无异常账号、无候选条目、无聚合结果，各用例按需覆盖
        when(analysisMapper.selectAbnormalUserIds(anyInt())).thenReturn(Collections.emptyList());
        when(analysisMapper.selectAbandonTargetsPage(any(), any(), anyInt(), anyLong(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(analysisMapper.selectProductAgg(any(), anyInt(), any())).thenReturn(Collections.emptyList());
        when(analysisMapper.selectSummary(any())).thenReturn(new RecallSummaryVO());
    }

    /**
     * 选品排序 = 先过「加购基数 + 弃购率」两道门槛，再按加购未买金额降序。
     *
     * <p>刻意只按弃购率排会被小样本霸榜：3 个人加购 0 人成交也是 100% 弃购率，
     * 排在 300 人加购的商品前面毫无业务意义，所以用金额做主排序键。
     */
    @Test
    void saveProducts_should_filter_by_base_and_rate_then_rank_by_amount() {
        CartRecallProduct bigMoney = product(1L, "高弃购大额商品", 300, 10, "900000.00");
        CartRecallProduct smallBase = product(2L, "小样本商品", 3, 0, "100.00");
        CartRecallProduct normalConversion = product(3L, "转化正常商品", 400, 300, "800000.00");
        CartRecallProduct tiny = product(4L, "基数不足商品", 2, 0, "50.00");
        when(analysisMapper.selectProductAgg(any(), anyInt(), any()))
                .thenReturn(List.of(normalConversion, smallBase, tiny, bigMoney));

        service.build(LocalDate.now());

        ArgumentCaptor<CartRecallProduct> captor = ArgumentCaptor.forClass(CartRecallProduct.class);
        verify(productMapper, times(2)).insert(captor.capture());
        List<CartRecallProduct> inserted = captor.getAllValues();

        // 转化正常的商品（弃购率 400/700=57% < 70%）与基数不足的商品都被过滤
        assertEquals(2, inserted.size());
        // 金额大的排前面，而不是弃购率 100% 的小样本商品
        assertEquals(1L, inserted.get(0).getProductId());
        assertEquals(1, inserted.get(0).getRankNo());
        assertEquals(2L, inserted.get(1).getProductId());
        assertEquals(2, inserted.get(1).getRankNo());
    }

    /**
     * 选品结果必须从人群池聚合 —— 池子已完成异常账号剔除，
     * 若改回直查 cart_item 就会让羊毛账号把长尾商品刷进榜（真实踩过的坑）
     */
    @Test
    void saveProducts_should_aggregate_from_recall_pool() {
        when(analysisMapper.selectProductAgg(any(), anyInt(), any()))
                .thenReturn(List.of(product(1L, "商品", 10, 0, "100.00")));

        service.build(LocalDate.now());

        verify(analysisMapper).selectProductAgg(any(), anyInt(), any());
    }

    /**
     * 幂等：写选品结果前先删当日旧数据，保证同一天重复执行结果一致
     */
    @Test
    void saveProducts_should_delete_existing_rows_before_insert() {
        when(analysisMapper.selectProductAgg(any(), anyInt(), any()))
                .thenReturn(List.of(product(1L, "商品", 10, 0, "100.00")));

        service.build(LocalDate.now());

        verify(productMapper).delete(any());
        verify(productMapper).insert(any(CartRecallProduct.class));
    }

    /**
     * 建议券面额按上下限收敛：面额过小无感，过大既烧钱又会训练出「等券」行为
     */
    @Test
    void suggested_amount_should_be_clamped_by_min_and_max() {
        RecallCandidateVO cheap = candidate(1L, 1L, 1L, "10.00");
        RecallCandidateVO normal = candidate(2L, 2L, 2L, "1000.00");
        RecallCandidateVO expensive = candidate(3L, 3L, 3L, "100000.00");
        when(analysisMapper.selectAbandonTargetsPage(any(), any(), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(cheap, normal, expensive));

        service.build(LocalDate.now());

        ArgumentCaptor<List<CartRecallTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(targetMapper).insertBatch(captor.capture());
        List<CartRecallTarget> inserted = captor.getValue();

        assertEquals(3, inserted.size());
        // 10 × 5% = 0.5，低于下限 5.00
        assertEquals(0, new BigDecimal("5.00").compareTo(inserted.get(0).getSuggestedAmount()));
        // 1000 × 5% = 50.00，落在区间内
        assertEquals(0, new BigDecimal("50.00").compareTo(inserted.get(1).getSuggestedAmount()));
        // 100000 × 5% = 5000，高于上限 200.00
        assertEquals(0, new BigDecimal("200.00").compareTo(inserted.get(2).getSuggestedAmount()));
    }

    /**
     * 羊毛党/爬虫账号（购物车条目数超阈值）整体剔除，不入召回池
     */
    @Test
    void abnormal_users_should_be_excluded_from_pool() {
        when(analysisMapper.selectAbnormalUserIds(anyInt())).thenReturn(List.of(999L));
        when(analysisMapper.selectAbandonTargetsPage(any(), any(), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(candidate(10L, 100L, 1L, "100.00"),
                        candidate(11L, 999L, 2L, "100.00")));

        service.build(LocalDate.now());

        ArgumentCaptor<List<CartRecallTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(targetMapper).insertBatch(captor.capture());
        List<CartRecallTarget> inserted = captor.getValue();

        assertEquals(1, inserted.size());
        assertEquals(100L, inserted.get(0).getUserId());
    }

    /**
     * 触达标识：有手机号或 openid 才算可触达 —— 都为空时发券也送不出去
     */
    @Test
    void reachable_flag_should_depend_on_phone_or_openid() {
        RecallCandidateVO withPhone = candidate(20L, 1L, 1L, "100.00");
        withPhone.setUserPhone("13800000001");
        RecallCandidateVO withOpenid = candidate(21L, 2L, 2L, "100.00");
        withOpenid.setUserOpenid("openid-abc");
        RecallCandidateVO unreachable = candidate(22L, 3L, 3L, "100.00");
        when(analysisMapper.selectAbandonTargetsPage(any(), any(), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(withPhone, withOpenid, unreachable));

        service.build(LocalDate.now());

        ArgumentCaptor<List<CartRecallTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(targetMapper).insertBatch(captor.capture());
        List<CartRecallTarget> inserted = captor.getValue();

        assertEquals(1, inserted.get(0).getReachable());
        assertEquals(1, inserted.get(1).getReachable());
        assertEquals(0, inserted.get(2).getReachable());
    }

    /**
     * 闲置时长按「首次加购时间」计算，用于窗口过滤与运营判断僵尸购物车占比
     */
    @Test
    void idle_hours_should_be_calculated_from_add_time() {
        RecallCandidateVO vo = candidate(30L, 1L, 1L, "100.00");
        vo.setAddTime(LocalDateTime.now().minusHours(30));
        when(analysisMapper.selectAbandonTargetsPage(any(), any(), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(vo));

        service.build(LocalDate.now());

        ArgumentCaptor<List<CartRecallTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(targetMapper).insertBatch(captor.capture());
        assertTrue(captor.getValue().get(0).getIdleHours() >= 30);
    }

    /**
     * 空数据不抛异常：新库或窗口内没有弃购时不应让任务失败
     */
    @Test
    void build_should_tolerate_empty_data() {
        RecallBuildResultVO result = service.build(LocalDate.now());

        assertNotNull(result);
        verify(targetMapper, never()).insertBatch(any());
        verify(productMapper, never()).insert(any(CartRecallProduct.class));
    }

    /**
     * 统计日期不可晚于今天（按配置时区），防止补数时写出未来日期的池子
     */
    @Test
    void build_should_reject_future_stat_date() {
        assertThrows(BusinessException.class, () -> service.build(LocalDate.now().plusDays(1)));
    }

    /**
     * 只清「待处理」条目：已发券/已转化的记录必须保留，否则活动无法复盘
     */
    @Test
    void build_should_only_clear_pending_rows() {
        service.build(LocalDate.now());

        verify(targetMapper).deletePending();
    }

    private CartRecallProduct product(Long productId, String name, int abandonUsers, int paidUsers, String amount) {
        CartRecallProduct item = new CartRecallProduct();
        item.setProductId(productId);
        item.setProductName(name);
        item.setAbandonUserCnt(abandonUsers);
        item.setAbandonItemCnt(abandonUsers);
        item.setAbandonAmount(new BigDecimal(amount));
        item.setPaidUserCnt(paidUsers);
        return item;
    }

    private RecallCandidateVO candidate(Long cartItemId, Long userId, Long productId, String amount) {
        RecallCandidateVO vo = new RecallCandidateVO();
        vo.setCartItemId(cartItemId);
        vo.setUserId(userId);
        vo.setSkuId(productId);
        vo.setProductId(productId);
        vo.setProductName("商品" + productId);
        vo.setSkuPrice(new BigDecimal(amount));
        vo.setQuantity(1);
        vo.setCartAmount(new BigDecimal(amount));
        vo.setAddTime(LocalDateTime.now().minusHours(30));
        return vo;
    }
}
