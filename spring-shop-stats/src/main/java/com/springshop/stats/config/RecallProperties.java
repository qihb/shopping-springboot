package com.springshop.stats.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 加购未买召回圈人配置
 *
 * <p>全部阈值配置化：召回窗口、防刷封顶、券面额区间都随运营策略变化，
 * 硬编码会导致每次调整都要改代码发版。
 */
@Component
@ConfigurationProperties(prefix = "stats.cart-recall")
public class RecallProperties {

    /** 是否启用定时圈人（测试环境置 false，避免 @SpringBootTest 真实触发调度） */
    private boolean enabled = true;

    /** 定时表达式，默认每天凌晨 4 点 */
    private String cron = "0 0 4 * * ?";

    /** 调度时区：cron 使用 JVM 默认时区，容器通常为 UTC，必须显式指定 */
    private String zone = "Asia/Shanghai";

    /** 最小闲置小时数：刚加购就发券是骚扰，且用户本来可能就要买 */
    private int minIdleHours = 24;

    /** 最大闲置天数：超过它算僵尸购物车，用户早忘了，打开率极低 */
    private int maxIdleDays = 7;

    /** 最小加购件数：过滤「随手加购一件」的低意向人群 */
    private int minQuantity = 1;

    /** 购物车条目数上限：超过视为羊毛党/爬虫账号，整体剔除 */
    private int maxCartSize = 200;

    /** 单用户单 SKU 件数封顶：用于聚合防刷，防单个账号用超大数量把商品顶到榜首 */
    private int qtyCap = 5;

    /** 选品输出条数 */
    private int topN = 100;

    /**
     * 选品最小加购未买用户数：只有 1 个人加购的商品弃购率恒为 100%，没有统计意义，
     * 必须先过基数门槛再看弃购率
     */
    private int minAbandonUsers = 3;

    /**
     * 选品最小弃购率：低于该值说明商品转化正常，用户没卡在价格上，发券撬不动
     */
    private BigDecimal minAbandonRate = new BigDecimal("0.70");

    /** 计算弃购率时，「已成交」的回看天数 */
    private int paidWindowDays = 30;

    /** 键集分页批大小 */
    private int pageSize = 2000;

    /** 批量写入条数 */
    private int writeBatchSize = 500;

    /** 建议券面额占加购金额的比例 */
    private BigDecimal couponRate = new BigDecimal("0.05");

    /** 建议券面额下限（元） */
    private BigDecimal minCouponAmount = new BigDecimal("5.00");

    /** 建议券面额上限（元） */
    private BigDecimal maxCouponAmount = new BigDecimal("200.00");

    /** 人群池保留天数：清理历史已处理记录 */
    private int retainDays = 90;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public int getMinIdleHours() { return minIdleHours; }
    public void setMinIdleHours(int minIdleHours) { this.minIdleHours = minIdleHours; }
    public int getMaxIdleDays() { return maxIdleDays; }
    public void setMaxIdleDays(int maxIdleDays) { this.maxIdleDays = maxIdleDays; }
    public int getMinQuantity() { return minQuantity; }
    public void setMinQuantity(int minQuantity) { this.minQuantity = minQuantity; }
    public int getMaxCartSize() { return maxCartSize; }
    public void setMaxCartSize(int maxCartSize) { this.maxCartSize = maxCartSize; }
    public int getQtyCap() { return qtyCap; }
    public void setQtyCap(int qtyCap) { this.qtyCap = qtyCap; }
    public int getTopN() { return topN; }
    public void setTopN(int topN) { this.topN = topN; }
    public int getMinAbandonUsers() { return minAbandonUsers; }
    public void setMinAbandonUsers(int minAbandonUsers) { this.minAbandonUsers = minAbandonUsers; }
    public BigDecimal getMinAbandonRate() { return minAbandonRate; }
    public void setMinAbandonRate(BigDecimal minAbandonRate) { this.minAbandonRate = minAbandonRate; }
    public int getPaidWindowDays() { return paidWindowDays; }
    public void setPaidWindowDays(int paidWindowDays) { this.paidWindowDays = paidWindowDays; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getWriteBatchSize() { return writeBatchSize; }
    public void setWriteBatchSize(int writeBatchSize) { this.writeBatchSize = writeBatchSize; }
    public BigDecimal getCouponRate() { return couponRate; }
    public void setCouponRate(BigDecimal couponRate) { this.couponRate = couponRate; }
    public BigDecimal getMinCouponAmount() { return minCouponAmount; }
    public void setMinCouponAmount(BigDecimal minCouponAmount) { this.minCouponAmount = minCouponAmount; }
    public BigDecimal getMaxCouponAmount() { return maxCouponAmount; }
    public void setMaxCouponAmount(BigDecimal maxCouponAmount) { this.maxCouponAmount = maxCouponAmount; }
    public int getRetainDays() { return retainDays; }
    public void setRetainDays(int retainDays) { this.retainDays = retainDays; }
}
