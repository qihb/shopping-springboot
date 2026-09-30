package com.springshop.cart.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车出参（列表 + 汇总）
 */
public class CartVO {

    private List<CartItemVO> items;

    /**
     * 购物车总件数（所有条目数量之和）
     */
    private Integer totalQuantity;

    /**
     * 已勾选件数（仅统计有效且勾选的条目）
     */
    private Integer checkedQuantity;

    /**
     * 已勾选金额合计（仅统计有效且勾选的条目）
     */
    private BigDecimal checkedAmount;

    public CartVO() {
    }

    public List<CartItemVO> getItems() { return items; }
    public void setItems(List<CartItemVO> items) { this.items = items; }
    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }
    public Integer getCheckedQuantity() { return checkedQuantity; }
    public void setCheckedQuantity(Integer checkedQuantity) { this.checkedQuantity = checkedQuantity; }
    public BigDecimal getCheckedAmount() { return checkedAmount; }
    public void setCheckedAmount(BigDecimal checkedAmount) { this.checkedAmount = checkedAmount; }
}
