package com.springshop.stats.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 待召回池概览
 *
 * <p>给运营回答「这批人值不值得发券」的前置问题：规模多大、触达覆盖率多少、有多少是僵尸购物车。
 * 触达覆盖率（reachableUsers / totalUsers）是决定走短信还是小程序订阅消息的关键依据 ——
 * {@code user.phone} 可空，覆盖不到的用户发券也送不出去。
 */
public class RecallSummaryVO {

    private LocalDate statDate;

    /** 待召回条目数 */
    private Integer totalItems;

    /** 待召回用户数（去重） */
    private Integer totalUsers;

    /** 待召回加购金额（元） */
    private BigDecimal totalAmount;

    /** 可触达条目数（有手机号或 openid） */
    private Integer reachableItems;

    /** 可触达用户数（去重） */
    private Integer reachableUsers;

    /** 有手机号的用户数 */
    private Integer phoneUsers;

    /** 有 openid 的用户数 */
    private Integer openidUsers;

    /** 平均闲置小时数 */
    private Integer avgIdleHours;

    /** 状态 = 待处理 */
    private Integer pendingItems;

    /** 状态 = 已发券 */
    private Integer sentItems;

    /** 状态 = 已转化 */
    private Integer convertedItems;

    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Integer getTotalItems() { return totalItems; }
    public void setTotalItems(Integer totalItems) { this.totalItems = totalItems; }
    public Integer getTotalUsers() { return totalUsers; }
    public void setTotalUsers(Integer totalUsers) { this.totalUsers = totalUsers; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public Integer getReachableItems() { return reachableItems; }
    public void setReachableItems(Integer reachableItems) { this.reachableItems = reachableItems; }
    public Integer getReachableUsers() { return reachableUsers; }
    public void setReachableUsers(Integer reachableUsers) { this.reachableUsers = reachableUsers; }
    public Integer getPhoneUsers() { return phoneUsers; }
    public void setPhoneUsers(Integer phoneUsers) { this.phoneUsers = phoneUsers; }
    public Integer getOpenidUsers() { return openidUsers; }
    public void setOpenidUsers(Integer openidUsers) { this.openidUsers = openidUsers; }
    public Integer getAvgIdleHours() { return avgIdleHours; }
    public void setAvgIdleHours(Integer avgIdleHours) { this.avgIdleHours = avgIdleHours; }
    public Integer getPendingItems() { return pendingItems; }
    public void setPendingItems(Integer pendingItems) { this.pendingItems = pendingItems; }
    public Integer getSentItems() { return sentItems; }
    public void setSentItems(Integer sentItems) { this.sentItems = sentItems; }
    public Integer getConvertedItems() { return convertedItems; }
    public void setConvertedItems(Integer convertedItems) { this.convertedItems = convertedItems; }
}
