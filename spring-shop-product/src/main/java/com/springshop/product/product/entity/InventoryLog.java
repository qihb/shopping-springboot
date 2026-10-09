package com.springshop.product.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 库存变更流水实体（append-only：只插不改不删）
 *
 * <p>刻意不带 {@code is_deleted} / {@code version}：它是「账」不是「状态」，
 * 与项目其他表不同（对应 {@code operation_log} 的写法）。
 *
 * <p>{@code changeType}：1 下单锁定 / 2 支付出库 / 3 取消释放 / 4 后台调整 / 5 导入初始化。
 */
@TableName("inventory_log")
public class InventoryLog {

    /** 变更类型：下单锁定 */
    public static final int TYPE_LOCK = 1;

    /** 变更类型：支付出库 */
    public static final int TYPE_OUTBOUND = 2;

    /** 变更类型：取消 / 超时释放 */
    public static final int TYPE_RELEASE = 3;

    /** 变更类型：后台调整 */
    public static final int TYPE_ADJUST = 4;

    /** 变更类型：导入 / 创建初始化 */
    public static final int TYPE_INIT = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skuId;

    /** 商品 id（冗余，便于按商品维度查） */
    private Long productId;

    /** 变更类型：1 下单锁定 / 2 支付出库 / 3 取消释放 / 4 后台调整 / 5 导入初始化 */
    private Integer changeType;

    private Integer stockBefore;

    private Integer stockAfter;

    private Integer lockedBefore;

    private Integer lockedAfter;

    /** 关联业务单号（订单号等） */
    private String bizNo;

    /** 操作人（后台调整时为管理员 id） */
    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    public InventoryLog() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Integer getChangeType() { return changeType; }
    public void setChangeType(Integer changeType) { this.changeType = changeType; }
    public Integer getStockBefore() { return stockBefore; }
    public void setStockBefore(Integer stockBefore) { this.stockBefore = stockBefore; }
    public Integer getStockAfter() { return stockAfter; }
    public void setStockAfter(Integer stockAfter) { this.stockAfter = stockAfter; }
    public Integer getLockedBefore() { return lockedBefore; }
    public void setLockedBefore(Integer lockedBefore) { this.lockedBefore = lockedBefore; }
    public Integer getLockedAfter() { return lockedAfter; }
    public void setLockedAfter(Integer lockedAfter) { this.lockedAfter = lockedAfter; }
    public String getBizNo() { return bizNo; }
    public void setBizNo(String bizNo) { this.bizNo = bizNo; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
