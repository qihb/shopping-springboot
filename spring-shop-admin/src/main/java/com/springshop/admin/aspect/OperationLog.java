package com.springshop.admin.aspect;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解
 *
 * <p>标注在管理后台 Controller 方法上，由 {@link OperationLogAspect} 统一记录
 * 操作人、参数、耗时与结果到 operation_log 表，供审计追溯。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /** 所属模块，如 商品管理 */
    String module() default "";

    /** 操作描述，如 新增商品 */
    String operation() default "";
}
