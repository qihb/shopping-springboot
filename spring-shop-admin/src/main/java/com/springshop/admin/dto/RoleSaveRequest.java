package com.springshop.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 角色保存入参（新增 / 修改共用）
 */
public class RoleSaveRequest {

    /** 角色名称 */
    @NotBlank(message = "角色名称不能为空")
    private String name;

    /** 角色编码（唯一，如 ADMIN / OPERATOR） */
    @NotBlank(message = "角色编码不能为空")
    private String code;

    /** 角色描述 */
    private String description;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
