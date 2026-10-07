package com.springshop.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 勾选 / 取消勾选入参
 */
@Schema(description = "购物车勾选 / 取消勾选请求")
public class CartCheckedRequest {

    @Schema(description = "是否勾选：true-已勾选，false-取消勾选")
    @NotNull(message = "勾选状态不能为空")
    private Boolean checked;

    public CartCheckedRequest() {
    }

    public Boolean getChecked() { return checked; }
    public void setChecked(Boolean checked) { this.checked = checked; }
}
