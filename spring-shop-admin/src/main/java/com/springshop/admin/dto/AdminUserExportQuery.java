package com.springshop.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 管理员导出查询条件
 *
 * <p>不继承 {@code AdminUserPageQuery}：导出没有分页概念，
 * 分页由后台线程按 {@code excel.task.export-page-size} 自己推进。
 */
@Schema(description = "管理员导出查询条件")
public class AdminUserExportQuery {

    /** 用户名，模糊匹配 */
    @Schema(description = "用户名，模糊匹配")
    private String username;

    /** 真实姓名，模糊匹配 */
    @Schema(description = "真实姓名，模糊匹配")
    private String realName;

    /** 账号状态：1 启用 / 0 禁用；为空表示不限 */
    @Schema(description = "账号状态：1 启用 / 0 禁用；为空表示不限")
    private Integer status;

    /** 指定管理员 id 列表（「导出选中」）；为空表示按筛选条件导出全部 */
    @Schema(description = "指定管理员 id 列表（「导出选中」）；为空表示按筛选条件导出全部")
    private List<Long> ids;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }
}
