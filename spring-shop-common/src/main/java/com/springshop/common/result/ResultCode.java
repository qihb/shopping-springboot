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

    // 业务码：Excel 异步任务（公共段，导入导出各模块共用）
    EXCEL_TASK_NOT_FOUND(41, "任务不存在或已被清理，请刷新任务列表"),
    EXCEL_TASK_DUPLICATE(42, "已有同类任务正在执行，请等待其完成后再提交"),
    EXCEL_TASK_BUSY(43, "系统繁忙，任务排队已满，请稍后重试"),
    EXCEL_TASK_DISABLED(44, "导入导出功能当前不可用"),
    EXCEL_TASK_NOT_FINISHED(45, "任务尚未完成，暂时无法下载结果"),
    EXCEL_TASK_NO_RESULT(46, "任务没有可下载的结果文件"),
    EXCEL_TASK_PARAMS_INVALID(47, "导出条件保存失败，请调整筛选条件后重新提交"),

    // 业务码：用户模块（1000 段）
    USERNAME_EXISTS(1001, "用户名已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "用户名或密码错误"),
    USER_DISABLED(1004, "账号已被禁用"),
    USER_LOCKED(1005, "登录失败次数过多，账号已临时锁定，请稍后再试"),
    MINIAPP_AUTH_FAILED(1006, "小程序登录失败，请稍后重试"),

    // 业务码：商品模块（2000 段）
    PRODUCT_CATEGORY_NOT_FOUND(2001, "商品分类不存在"),
    PRODUCT_CATEGORY_HAS_CHILDREN(2002, "分类下存在子分类，不可删除"),
    PRODUCT_CATEGORY_HAS_PRODUCTS(2003, "分类下存在商品，不可删除"),
    PRODUCT_NOT_FOUND(2010, "商品不存在"),
    PRODUCT_SKU_NOT_FOUND(2011, "SKU 不存在"),
    PRODUCT_SKU_CODE_DUPLICATE(2012, "SKU 编码重复"),
    PRODUCT_SKU_EMPTY(2013, "商品至少需要一个 SKU"),
    PRODUCT_OFF_SHELF(2014, "商品已下架"),
    PRODUCT_IMPORT_FILE_INVALID(2020, "导入文件不合法，请下载模板后重新填写"),

    // 业务码：购物车模块（3000 段）
    CART_ITEM_NOT_FOUND(3001, "购物车条目不存在"),
    CART_QUANTITY_INVALID(3002, "购买数量不合法"),
    CART_STOCK_INSUFFICIENT(3003, "库存不足"),
    CART_SKU_DISABLED(3004, "该规格已停售"),

    // 业务码：订单模块（4000 段）
    ORDER_ADDRESS_NOT_FOUND(4001, "收货地址不存在"),
    ORDER_CART_EMPTY(4002, "请先勾选要下单的商品"),
    ORDER_SKU_UNAVAILABLE(4003, "商品已下架或规格已停售"),
    ORDER_STOCK_INSUFFICIENT(4004, "商品库存不足"),
    ORDER_NOT_FOUND(4005, "订单不存在"),
    ORDER_STATUS_ILLEGAL(4006, "当前订单状态不支持该操作"),

    // 业务码：管理后台模块（5000 段）
    ADMIN_USER_NOT_FOUND(5001, "管理员不存在"),
    ADMIN_PASSWORD_ERROR(5002, "用户名或密码错误"),
    ADMIN_DISABLED(5003, "账号已被禁用"),
    ADMIN_LOCKED(5004, "登录失败次数过多，账号已临时锁定，请稍后再试"),
    ADMIN_TOKEN_INVALID(5005, "登录已失效，请重新登录"),
    ADMIN_USERNAME_EXISTS(5006, "管理员用户名已存在"),
    ADMIN_OLD_PASSWORD_ERROR(5007, "原密码错误"),
    ADMIN_SELF_OPERATION_FORBIDDEN(5008, "不能对当前登录的管理员账号执行该操作"),
    ADMIN_IMPORT_FILE_INVALID(5009, "导入文件不合法，请下载模板后重新填写"),
    ADMIN_ROLE_NOT_FOUND(5010, "角色不存在"),
    ADMIN_ROLE_CODE_EXISTS(5011, "角色编码已存在"),
    ADMIN_ROLE_IN_USE(5012, "角色已分配给管理员，不可删除"),
    ADMIN_MENU_NOT_FOUND(5020, "菜单不存在"),
    ADMIN_MENU_HAS_CHILDREN(5021, "菜单存在子节点，不可删除"),

    // 业务码：支付模块（6000 段）
    PAY_ORDER_NOT_FOUND(6001, "订单不存在"),
    PAY_FORBIDDEN(6002, "无权支付该订单"),
    PAY_STATUS_ILLEGAL(6003, "订单当前状态不可支付"),

    // 业务码：统计分析模块（7000 段）
    STATS_RECALL_DATE_INVALID(7001, "统计日期不合法，不可晚于今天"),
    STATS_RECALL_PARAM_INVALID(7002, "圈人参数不合法"),
    STATS_RECALL_RUNNING(7003, "圈人任务正在执行中，请稍后重试");

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
