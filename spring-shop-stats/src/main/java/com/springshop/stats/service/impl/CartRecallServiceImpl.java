package com.springshop.stats.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.stats.config.RecallProperties;
import com.springshop.stats.entity.CartRecallProduct;
import com.springshop.stats.entity.CartRecallTarget;
import com.springshop.stats.mapper.CartRecallProductMapper;
import com.springshop.stats.mapper.CartRecallTargetMapper;
import com.springshop.stats.mapper.RecallAnalysisMapper;
import com.springshop.stats.service.CartRecallService;
import com.springshop.stats.vo.RecallBuildResultVO;
import com.springshop.stats.vo.RecallCandidateVO;
import com.springshop.stats.vo.RecallSummaryVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 加购未买召回圈人服务实现
 *
 * <p><b>为什么「加购未买」可以直接从 cart_item 读</b>：下单成功时
 * {@code OrderServiceImpl.create()} 会物理删除本次下单的购物车条目，
 * 因此 {@code cart_item} 里剩下的按定义就是「加购了但还没下单」的集合；
 * 且 {@code create_time} 只在首次加购时写入（后续改数量/改勾选走 updateById，只刷 update_time），
 * 所以它就是准确的「首次加购时刻」，可直接用于闲置时长窗口过滤。
 *
 * <p><b>时区处理</b>：所有「当前时刻」与「今天」都按配置时区（默认 Asia/Shanghai）计算，
 * 不用 JVM 默认时区 —— 容器通常为 UTC，否则跑批日与闲置时长会整体错位。
 *
 * <p><b>事务边界</b>：整个 build 在一个事务内完成「清空待处理 + 重建池子 + 写选品」，
 * 保证下游读到的人群池是完整的、不会读到重建到一半的半成品。
 * 代价是事务持续时间等于跑批时长；当前数据量级（万级）下可接受，
 * 若未来到千万级，应改为「写临时表 + 原子切换」以避免长事务撑大 undo 与持有 MDL。
 */
@Service
public class CartRecallServiceImpl implements CartRecallService {

    private static final Logger log = LoggerFactory.getLogger(CartRecallServiceImpl.class);

    private final RecallAnalysisMapper analysisMapper;
    private final CartRecallTargetMapper targetMapper;
    private final CartRecallProductMapper productMapper;
    private final RecallProperties props;

    public CartRecallServiceImpl(RecallAnalysisMapper analysisMapper,
                                 CartRecallTargetMapper targetMapper,
                                 CartRecallProductMapper productMapper,
                                 RecallProperties props) {
        this.analysisMapper = analysisMapper;
        this.targetMapper = targetMapper;
        this.productMapper = productMapper;
        this.props = props;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecallBuildResultVO build(LocalDate statDate) {
        long startedAt = System.currentTimeMillis();
        ZoneId zone = resolveZone();
        LocalDate today = LocalDate.now(zone);
        if (statDate == null || statDate.isAfter(today)) {
            throw new BusinessException(ResultCode.STATS_RECALL_DATE_INVALID);
        }

        // 统一按配置时区取「当前时刻」，避免 JVM 默认时区（容器常为 UTC）导致窗口与闲置时长整体偏移
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime idleBefore = now.minusHours(props.getMinIdleHours());
        LocalDateTime idleAfter = now.minusDays(props.getMaxIdleDays());
        LocalDateTime paidFrom = now.minusDays(props.getPaidWindowDays());

        // 羊毛党/爬虫账号整体剔除：先单独查出，避免在主查询里挂关联子查询反复全表扫描
        Set<Long> abnormalUsers = new HashSet<>(analysisMapper.selectAbnormalUserIds(props.getMaxCartSize()));

        // 只清「待处理」条目：已发券/已转化/已失效的记录要留痕，否则活动无法复盘
        int removed = targetMapper.deletePending();

        // productId -> {条目数, 闲置小时累计}，用于回填选品结果的平均闲置时长；
        // 只累计按商品维度的聚合值，不保留全量明细，内存占用与商品数同阶而非与条目数同阶
        Map<Long, long[]> idleAcc = new HashMap<>();
        List<CartRecallTarget> buffer = new ArrayList<>(props.getWriteBatchSize());
        long lastId = 0L;
        int scanned = 0;

        while (true) {
            List<RecallCandidateVO> page = analysisMapper.selectAbandonTargetsPage(
                    idleBefore, idleAfter, props.getMinQuantity(), lastId, props.getPageSize());
            if (page.isEmpty()) {
                break;
            }
            // 游标始终推进到本页最后一条，即使该条被过滤掉也要推进，否则会死循环
            lastId = page.get(page.size() - 1).getCartItemId();
            scanned += page.size();

            for (RecallCandidateVO candidate : page) {
                if (abnormalUsers.contains(candidate.getUserId())) {
                    continue;
                }
                CartRecallTarget target = toTarget(candidate, statDate, now);
                buffer.add(target);

                long[] acc = idleAcc.computeIfAbsent(candidate.getProductId(), key -> new long[2]);
                acc[0]++;
                acc[1] += target.getIdleHours();

                if (buffer.size() >= props.getWriteBatchSize()) {
                    // 传防御性副本：buffer 随后会被 clear()，直接传引用会让调用方持有的
                    // 同一列表对象被清空（生产上 MyBatis 是即时消费不感知，但这是隐式耦合）
                    targetMapper.insertBatch(List.copyOf(buffer));
                    buffer.clear();
                }
            }
            if (page.size() < props.getPageSize()) {
                break;
            }
        }
        if (!buffer.isEmpty()) {
            targetMapper.insertBatch(List.copyOf(buffer));
            buffer.clear();
        }

        int productCount = saveProducts(statDate, paidFrom, idleAcc);
        int cleaned = cleanExpired(statDate);

        // 规模、触达覆盖率等统计直接由 DB 汇总，避免在内存里维护全量 userId 集合
        RecallSummaryVO summary = analysisMapper.selectSummary(statDate);

        RecallBuildResultVO result = new RecallBuildResultVO();
        result.setStatDate(statDate);
        // 注意不能写成 summary == null ? 0 : summary.getTotalItems()：三元表达式里 int 与 Integer
        // 混用会对 Integer 分支做拆箱，字段为 null 时直接 NPE
        result.setTargetCount(summary == null ? 0 : nullToZero(summary.getTotalItems()));
        result.setUserCount(summary == null ? 0 : nullToZero(summary.getTotalUsers()));
        result.setReachableCount(summary == null ? 0 : nullToZero(summary.getReachableItems()));
        result.setProductCount(productCount);
        result.setExcludedUserCount(abnormalUsers.size());
        result.setDurationMs(System.currentTimeMillis() - startedAt);
        log.info("圈人完成 statDate={} 扫描={} 清空待处理={} 入池={} 用户={} 可触达={} 选品={} 剔除异常账号={} 清理过期={} 耗时={}ms",
                statDate, scanned, removed, result.getTargetCount(), result.getUserCount(),
                result.getReachableCount(), productCount, abnormalUsers.size(), cleaned, result.getDurationMs());
        return result;
    }

    @Override
    public LocalDate currentStatDate() {
        return LocalDate.now(resolveZone());
    }

    @Override
    public RecallSummaryVO summary(LocalDate statDate) {
        RecallSummaryVO summary = analysisMapper.selectSummary(statDate);
        return summary == null ? new RecallSummaryVO() : summary;
    }

    @Override
    public List<CartRecallProduct> listProducts(LocalDate statDate, Integer limit) {
        return productMapper.selectList(new LambdaQueryWrapper<CartRecallProduct>()
                .eq(CartRecallProduct::getStatDate, statDate)
                .orderByAsc(CartRecallProduct::getRankNo)
                .last("LIMIT " + normalizeLimit(limit, 100)));
    }

    @Override
    public List<CartRecallTarget> listTargets(LocalDate statDate, Integer limit, boolean reachableOnly) {
        LambdaQueryWrapper<CartRecallTarget> wrapper = new LambdaQueryWrapper<CartRecallTarget>()
                .eq(CartRecallTarget::getStatDate, statDate);
        if (reachableOnly) {
            wrapper.eq(CartRecallTarget::getReachable, 1);
        }
        wrapper.orderByDesc(CartRecallTarget::getCartAmount)
                .last("LIMIT " + normalizeLimit(limit, 100));
        return targetMapper.selectList(wrapper);
    }

    /**
     * 选品：从人群池聚合出「值得发券」的商品
     *
     * <p>为什么不按「加购人数」排序：那选出的是热门商品，而热门商品本来就好卖，
     * 给它打折等于白送利润。
     *
     * <p>排序规则 = 先过两道门槛（加购基数、弃购率），再按加购未买金额降序：
     * <ul>
     *   <li>弃购率高 → 用户想买却卡在价格上，券能撬动；</li>
     *   <li>加购未买金额大 → 撬动空间大，一张券能带动的 GMV 更多；</li>
     *   <li>只按弃购率排会被小样本霸榜：13 个人加购 0 人成交也是 100% 弃购率，
     *       但它排在 300 人加购的商品前面毫无业务意义。</li>
     * </ul>
     */
    private int saveProducts(LocalDate statDate, LocalDateTime paidFrom, Map<Long, long[]> idleAcc) {
        List<CartRecallProduct> aggregated = analysisMapper.selectProductAgg(
                statDate, props.getQtyCap(), paidFrom);

        List<CartRecallProduct> ranked = new ArrayList<>();
        for (CartRecallProduct item : aggregated) {
            int abandonUsers = nullToZero(item.getAbandonUserCnt());
            // 基数门槛：只有 1 个人加购的商品弃购率恒为 100%，没有统计意义
            if (abandonUsers < props.getMinAbandonUsers()) {
                continue;
            }
            int paidUsers = nullToZero(item.getPaidUserCnt());
            BigDecimal rate = abandonRate(abandonUsers, paidUsers);
            // 弃购率门槛：转化正常的商品说明用户没卡在价格上，发券撬不动
            if (rate.compareTo(props.getMinAbandonRate()) < 0) {
                continue;
            }
            item.setAbandonRate(rate);
            item.setAvgIdleHours(avgIdleHours(idleAcc.get(item.getProductId())));
            item.setStatDate(statDate);
            ranked.add(item);
        }

        // 并列必须有确定性 tie-break，否则每次跑批同分商品顺序会抖，运营看到的榜会跳
        ranked.sort(Comparator
                .comparing(CartRecallProduct::getAbandonAmount, Comparator.reverseOrder())
                .thenComparing(CartRecallProduct::getAbandonUserCnt, Comparator.reverseOrder())
                .thenComparing(CartRecallProduct::getProductId));

        List<CartRecallProduct> top = ranked.size() > props.getTopN()
                ? ranked.subList(0, props.getTopN())
                : ranked;

        // 先删当日旧结果再写入：任务可能因重启补跑或手动补数重复执行，保证同一天跑 N 次结果一致
        productMapper.delete(new LambdaQueryWrapper<CartRecallProduct>()
                .eq(CartRecallProduct::getStatDate, statDate));
        for (int i = 0; i < top.size(); i++) {
            CartRecallProduct item = top.get(i);
            item.setId(null);
            item.setRankNo(i + 1);
            productMapper.insert(item);
        }
        return top.size();
    }

    /**
     * 清理保留期之外的已处理记录：待处理条目每轮跑批都会重建，只有已发券/已转化/已失效的行会累积
     */
    private int cleanExpired(LocalDate statDate) {
        return targetMapper.delete(new LambdaQueryWrapper<CartRecallTarget>()
                .ne(CartRecallTarget::getStatus, 0)
                .lt(CartRecallTarget::getStatDate, statDate.minusDays(props.getRetainDays())));
    }

    /**
     * 候选条目 → 人群池条目：补齐闲置时长、触达标识与建议券面额
     */
    private CartRecallTarget toTarget(RecallCandidateVO candidate, LocalDate statDate, LocalDateTime now) {
        CartRecallTarget target = new CartRecallTarget();
        target.setUserId(candidate.getUserId());
        target.setSkuId(candidate.getSkuId());
        target.setProductId(candidate.getProductId());
        target.setProductName(candidate.getProductName());
        target.setMainImage(candidate.getMainImage());
        target.setSkuPrice(candidate.getSkuPrice());
        target.setQuantity(candidate.getQuantity());
        target.setCartAmount(candidate.getCartAmount());
        target.setAddTime(candidate.getAddTime());

        long idle = Duration.between(candidate.getAddTime(), now).toHours();
        target.setIdleHours((int) Math.max(idle, 0));

        target.setUserPhone(candidate.getUserPhone());
        target.setUserOpenid(candidate.getUserOpenid());
        boolean reachable = hasText(candidate.getUserPhone()) || hasText(candidate.getUserOpenid());
        target.setReachable(reachable ? 1 : 0);

        target.setSuggestedAmount(suggestedAmount(candidate.getCartAmount()));
        target.setStatus(0);
        target.setStatDate(statDate);
        return target;
    }

    /**
     * 建议券面额 = 加购金额 × 配置比例，并按上下限收敛
     *
     * <p>面额刻意做小：给「加购未买」的人发券等于告诉用户「加购但不付款就能拿折扣」，
     * 面额过大既烧钱又会训练出「等券」行为，长期拉低客单价。
     */
    private BigDecimal suggestedAmount(BigDecimal cartAmount) {
        if (cartAmount == null || cartAmount.signum() <= 0) {
            return props.getMinCouponAmount();
        }
        BigDecimal amount = cartAmount.multiply(props.getCouponRate()).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(props.getMinCouponAmount()) < 0) {
            return props.getMinCouponAmount();
        }
        if (amount.compareTo(props.getMaxCouponAmount()) > 0) {
            return props.getMaxCouponAmount();
        }
        return amount;
    }

    private BigDecimal abandonRate(int abandonUsers, int paidUsers) {
        int base = abandonUsers + paidUsers;
        if (base <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(abandonUsers)
                .divide(BigDecimal.valueOf(base), 4, RoundingMode.HALF_UP);
    }

    private int avgIdleHours(long[] acc) {
        if (acc == null || acc[0] <= 0) {
            return 0;
        }
        return (int) (acc[1] / acc[0]);
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ZoneId resolveZone() {
        try {
            return ZoneId.of(props.getZone());
        } catch (Exception e) {
            log.warn("非法时区配置 stats.cart-recall.zone={}，回退 Asia/Shanghai", props.getZone());
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private int normalizeLimit(Integer limit, int defaultValue) {
        if (limit == null || limit <= 0) {
            return defaultValue;
        }
        return Math.min(limit, 1000);
    }
}
