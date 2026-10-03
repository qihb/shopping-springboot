package com.springshop.admin.vo;

import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 管理员导出模型
 */
public class AdminUserExportRow {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ExcelProperty("管理员ID")
    private Long id;

    @ExcelProperty("用户名")
    private String username;

    @ExcelProperty("姓名")
    private String realName;

    @ExcelProperty("手机号")
    private String phone;

    @ExcelProperty("角色")
    private String roleNames;

    @ExcelProperty("状态")
    private String statusName;

    @ExcelProperty("最近登录时间")
    private String lastLoginTime;

    @ExcelProperty("创建时间")
    private String createTime;

    public AdminUserExportRow() {
    }

    public static AdminUserExportRow from(AdminUserVO vo) {
        AdminUserExportRow row = new AdminUserExportRow();
        row.setId(vo.getId());
        row.setUsername(vo.getUsername());
        row.setRealName(vo.getRealName());
        row.setPhone(vo.getPhone());
        row.setRoleNames(joinRoleNames(vo.getRoleNames()));
        row.setStatusName(statusLabel(vo.getStatus()));
        row.setLastLoginTime(formatTime(vo.getLastLoginTime()));
        row.setCreateTime(formatTime(vo.getCreateTime()));
        return row;
    }

    private static String joinRoleNames(List<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return "";
        }
        return String.join("、", roleNames);
    }

    private static String statusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return status == 1 ? "启用" : "禁用";
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FORMAT);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRoleNames() { return roleNames; }
    public void setRoleNames(String roleNames) { this.roleNames = roleNames; }
    public String getStatusName() { return statusName; }
    public void setStatusName(String statusName) { this.statusName = statusName; }
    public String getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(String lastLoginTime) { this.lastLoginTime = lastLoginTime; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
