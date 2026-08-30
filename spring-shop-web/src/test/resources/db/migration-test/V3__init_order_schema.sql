-- 测试环境用迁移脚本 V3（与主脚本 db/migration/V3__init_order_schema.sql 内容一致）
-- 注意：H2 中索引名是库级唯一，主脚本里跨表重名的索引在此修正：
--   shipping_address.idx_user_id → idx_address_user_id
--   orders.idx_user_id           → idx_order_user_id
--   orders.idx_status            → idx_order_status（与 product.idx_status 重名）
CREATE TABLE IF NOT EXISTS `shipping_address` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        BIGINT       NOT NULL COMMENT '所属用户 id',
    `receiver_name`  VARCHAR(50)  NOT NULL COMMENT '收货人姓名',
    `receiver_phone` VARCHAR(20)  NOT NULL COMMENT '收货人手机号',
    `province`       VARCHAR(50)  NOT NULL COMMENT '省',
    `city`           VARCHAR(50)  NOT NULL COMMENT '市',
    `district`       VARCHAR(50)  NOT NULL COMMENT '区/县',
    `detail_address` VARCHAR(200) NOT NULL COMMENT '详细地址',
    `is_default`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认地址：1 是 / 0 否',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_address_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='收货地址表';

CREATE TABLE IF NOT EXISTS `cart_item` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '所属用户 id',
    `sku_id`      BIGINT   NOT NULL COMMENT 'SKU id',
    `quantity`    INT      NOT NULL DEFAULT 1 COMMENT '购买数量',
    `checked`     TINYINT  NOT NULL DEFAULT 1 COMMENT '是否勾选：1 勾选 / 0 未勾选',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_sku` (`user_id`, `sku_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='购物车表';

CREATE TABLE IF NOT EXISTS `orders` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`         VARCHAR(32)   NOT NULL COMMENT '订单号',
    `user_id`          BIGINT        NOT NULL COMMENT '下单用户 id',
    `total_amount`     DECIMAL(10,2) NOT NULL COMMENT '商品总金额（元）',
    `pay_amount`       DECIMAL(10,2) NOT NULL COMMENT '实付金额（元）',
    `status`           TINYINT       NOT NULL COMMENT '订单状态：1 待付款 / 2 待发货 / 3 待收货 / 4 已完成 / 5 已取消 / 6 已退款',
    `receiver_name`    VARCHAR(50)   NOT NULL COMMENT '收货人姓名（下单时快照）',
    `receiver_phone`   VARCHAR(20)   NOT NULL COMMENT '收货人手机号（下单时快照）',
    `receiver_address` VARCHAR(255)  NOT NULL COMMENT '收货地址（下单时快照）',
    `remark`           VARCHAR(255)  DEFAULT NULL COMMENT '买家备注',
    `pay_time`         DATETIME      DEFAULT NULL COMMENT '支付时间',
    `ship_time`        DATETIME      DEFAULT NULL COMMENT '发货时间',
    `finish_time`      DATETIME      DEFAULT NULL COMMENT '完成时间',
    `cancel_time`      DATETIME      DEFAULT NULL COMMENT '取消时间',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`          INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_order_user_id` (`user_id`),
    KEY `idx_order_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单表';

CREATE TABLE IF NOT EXISTS `order_item` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`      BIGINT        NOT NULL COMMENT '所属订单 id',
    `product_id`    BIGINT        NOT NULL COMMENT '商品 id',
    `sku_id`        BIGINT        NOT NULL COMMENT 'SKU id',
    `product_name`  VARCHAR(100)  NOT NULL COMMENT '商品名称（快照）',
    `sku_specs`     VARCHAR(255)  DEFAULT NULL COMMENT 'SKU 规格（快照）',
    `product_image` VARCHAR(255)  DEFAULT NULL COMMENT '商品主图（快照）',
    `price`         DECIMAL(10,2) NOT NULL COMMENT '成交单价（元）',
    `quantity`      INT           NOT NULL COMMENT '购买数量',
    `subtotal`      DECIMAL(10,2) NOT NULL COMMENT '小计金额（元）',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`    TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`       INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单明细表';
