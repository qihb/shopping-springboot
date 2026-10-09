package com.springshop.product.product.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 库存流水视图对象
 */
@Schema(description = "库存变更流水")
public class InventoryLogVO {

    @Schema(description = "流水 id")
    private Long id;

    @Schema(description = "SKU id")
    private Long skuId;

    @Schema(description = "商品 id")
    private Long productId;

    @Schema(description = "变更类型：1 下单锁定 / 2 支付出库 / 3 取消释放 / 4 后台调整 / 5 导入初始化")
    private Integer changeType;

    @Schema(description = "变更前在库实物量")
    private Integer stockBefore;

    @Schema(description = "变更后在库实物量")
    private Integer stockAfter;

    @Schema(description = "变更前锁定量")
    private Integer lockedBefore;

    @Schema(description = "变更后锁定量")
    private Integer lockedAfter;

    @Schema(description = "关联业务单号（订单号等）")
    private String bizNo;

    @Schema(description = "操作人（后台调整时为管理员 id）")
    private Long operatorId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public InventoryLogVO() {
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
