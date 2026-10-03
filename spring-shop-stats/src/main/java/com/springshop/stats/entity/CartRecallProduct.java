package com.springshop.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 加购未买选品结果（按商品聚合的每日快照）
 *
 * <p>只存聚合结果，行数 = 每日 TOP N，可长期保留用于趋势对比。
 * 弃购率 = 加购未买用户数 / (加购未买用户数 + 已成交用户数)，衡量「想买但卡在价格上」的程度，
 * 是比「加购人数」更有效的选品依据 —— 热门商品本来就好卖，给它打折是白送利润。
 */
@TableName("cart_recall_product")
public class CartRecallProduct {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;

    private Integer rankNo;

    private Long productId;

    private String productName;

    private Integer abandonUserCnt;

    private Integer abandonItemCnt;

    private BigDecimal abandonAmount;

    private Integer paidUserCnt;

    private BigDecimal abandonRate;

    private Integer avgIdleHours;

    private LocalDateTime createTime;

    public CartRecallProduct() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Integer getRankNo() { return rankNo; }
    public void setRankNo(Integer rankNo) { this.rankNo = rankNo; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Integer getAbandonUserCnt() { return abandonUserCnt; }
    public void setAbandonUserCnt(Integer abandonUserCnt) { this.abandonUserCnt = abandonUserCnt; }
    public Integer getAbandonItemCnt() { return abandonItemCnt; }
    public void setAbandonItemCnt(Integer abandonItemCnt) { this.abandonItemCnt = abandonItemCnt; }
    public BigDecimal getAbandonAmount() { return abandonAmount; }
    public void setAbandonAmount(BigDecimal abandonAmount) { this.abandonAmount = abandonAmount; }
    public Integer getPaidUserCnt() { return paidUserCnt; }
    public void setPaidUserCnt(Integer paidUserCnt) { this.paidUserCnt = paidUserCnt; }
    public BigDecimal getAbandonRate() { return abandonRate; }
    public void setAbandonRate(BigDecimal abandonRate) { this.abandonRate = abandonRate; }
    public Integer getAvgIdleHours() { return avgIdleHours; }
    public void setAvgIdleHours(Integer avgIdleHours) { this.avgIdleHours = avgIdleHours; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
