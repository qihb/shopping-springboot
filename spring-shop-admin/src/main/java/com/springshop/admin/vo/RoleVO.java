package com.springshop.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 角色信息（对外不暴露逻辑删除等内部字段）
 */
@Schema(description = "角色信息")
public class RoleVO {

    @Schema(description = "角色 id")
    private Long id;

    @Schema(description = "角色名称")
    private String name;

    @Schema(description = "角色编码（唯一，如 ADMIN / OPERATOR）")
    private String code;

    @Schema(description = "角色描述")
    private String description;

    @Schema(description = "状态：1 启用 / 0 停用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
