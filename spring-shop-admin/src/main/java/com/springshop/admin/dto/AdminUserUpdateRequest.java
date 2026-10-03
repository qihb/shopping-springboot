package com.springshop.admin.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改管理员入参
 *
 * <p>刻意不包含密码与角色：改密码走「重置密码」接口，改角色走「分配角色」接口。
 * 每个接口只做一件事，避免基本信息编辑被当成改密或提权的旁路。
 * 用户名也不可修改：它是审计日志里的操作人标识，变更会让历史日志对不上人。
 */
public class AdminUserUpdateRequest {

    /** 真实姓名 */
    @Size(max = 50, message = "姓名长度不超过 50")
    private String realName;

    /** 手机号 */
    @Size(max = 20, message = "手机号长度不超过 20")
    private String phone;

    /** 账号状态：1 启用 / 0 禁用，为 null 表示不修改 */
    private Integer status;

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
