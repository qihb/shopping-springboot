-- Flyway 版本化迁移 V5：支付模块建表（spring-shop-pay）
-- 设计要点：
-- 1. 支付记录以订单号为唯一键（uk_pay_record_order_no），一个订单只允许一条成功支付记录，
--    数据库层兜底 mockPay 幂等，防并发双击产生重复流水；
-- 2. 金额取订单快照 pay_amount，DECIMAL(10,2)（元），不使用浮点型，避免精度问题；
-- 3. 支付记录为只增不改的资金流水，无逻辑删除与乐观锁字段。

-- 支付记录表（模拟支付网关一次到位，status 固定为支付成功）
CREATE TABLE `pay_record` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`    VARCHAR(64)   NOT NULL COMMENT '订单号',
    `user_id`     BIGINT        NOT NULL COMMENT '支付用户 id',
    `amount`      DECIMAL(10,2) NOT NULL COMMENT '支付金额（元）',
    `status`      TINYINT       NOT NULL DEFAULT 1 COMMENT '支付状态：1 支付成功（模拟网关一次到位）',
    `pay_time`    DATETIME      NOT NULL COMMENT '支付时间',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pay_record_order_no` (`order_no`),
    KEY `idx_pay_record_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='支付记录表';
