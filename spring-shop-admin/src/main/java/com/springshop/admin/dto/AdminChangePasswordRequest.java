package com.springshop.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改当前登录管理员自己的密码入参
 *
 * <p>与「重置密码」的区别：本接口要求校验原密码，属于用户自助改密；
 * 重置密码是管理员对他人账号的强制操作，不需要原密码。
 */
@Schema(description = "修改当前登录管理员密码入参")
public class AdminChangePasswordRequest {

    /** 原密码 */
    @Schema(description = "原密码")
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /** 新密码 */
    @Schema(description = "新密码")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
    private String newPassword;

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
