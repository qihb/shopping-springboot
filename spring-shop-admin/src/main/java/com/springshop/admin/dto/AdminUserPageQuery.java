package com.springshop.admin.dto;

import com.springshop.common.dto.PageQuery;

/**
 * 管理员分页查询入参
 */
public class AdminUserPageQuery extends PageQuery {

    /** 用户名，模糊匹配 */
    private String username;

    /** 真实姓名，模糊匹配 */
    private String realName;

    /** 账号状态：1 启用 / 0 禁用 */
    private Integer status;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
