package com.springshop.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新增管理员入参
 */
@Schema(description = "新增管理员入参")
public class AdminUserCreateRequest {

    /** 登录用户名（唯一） */
    @Schema(description = "登录用户名（唯一）")
    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不超过 50")
    private String username;

    /** 初始密码（明文传输，落库前 BCrypt 加密） */
    @Schema(description = "初始密码（明文传输，落库前 BCrypt 加密）")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
    private String password;

    /** 真实姓名 */
    @Schema(description = "真实姓名")
    @Size(max = 50, message = "姓名长度不超过 50")
    private String realName;

    /** 手机号 */
    @Schema(description = "手机号")
    @Size(max = 20, message = "手机号长度不超过 20")
    private String phone;

    /** 账号状态：1 启用 / 0 禁用，缺省启用 */
    @Schema(description = "账号状态：1 启用 / 0 禁用，缺省启用")
    private Integer status;

    /** 分配的角色 id 集合，可为空表示暂不分配角色 */
    @Schema(description = "分配的角色 id 集合，可为空表示暂不分配角色")
    private List<Long> roleIds;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

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

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }
}
