package com.springshop.common.result;

/**
 * 响应码枚举
 */
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    SYSTEM_ERROR(500, "系统内部错误"),

    // 业务码：用户模块（1000 段）
    USERNAME_EXISTS(1001, "用户名已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "用户名或密码错误"),
    USER_DISABLED(1004, "账号已被禁用"),

    // 业务码：管理后台模块（5000 段）
    ADMIN_USER_NOT_FOUND(5001, "管理员不存在"),
    ADMIN_PASSWORD_ERROR(5002, "用户名或密码错误"),
    ADMIN_DISABLED(5003, "账号已被禁用"),
    ADMIN_LOCKED(5004, "登录失败次数过多，账号已临时锁定，请稍后再试"),
    ADMIN_TOKEN_INVALID(5005, "登录已失效，请重新登录"),
    ADMIN_ROLE_NOT_FOUND(5010, "角色不存在"),
    ADMIN_ROLE_CODE_EXISTS(5011, "角色编码已存在"),
    ADMIN_ROLE_IN_USE(5012, "角色已分配给管理员，不可删除"),
    ADMIN_MENU_NOT_FOUND(5020, "菜单不存在"),
    ADMIN_MENU_HAS_CHILDREN(5021, "菜单存在子节点，不可删除");

    private final Integer code;

    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
