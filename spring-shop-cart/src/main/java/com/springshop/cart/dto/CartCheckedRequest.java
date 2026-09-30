package com.springshop.cart.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 勾选 / 取消勾选入参
 */
public class CartCheckedRequest {

    @NotNull(message = "勾选状态不能为空")
    private Boolean checked;

    public CartCheckedRequest() {
    }

    public Boolean getChecked() { return checked; }
    public void setChecked(Boolean checked) { this.checked = checked; }
}
