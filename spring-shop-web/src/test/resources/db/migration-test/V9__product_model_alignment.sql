-- 测试环境用迁移脚本 V9（与主脚本 db/migration/V9__product_model_alignment.sql 对应）
-- H2 兼容：ALTER 不支持 MySQL 的 AFTER 子句与 ADD KEY，改用「先 ADD COLUMN，再 CREATE INDEX」。
-- 索引名已在主脚本中前缀化、全局唯一，两副本保持一致。

CREATE TABLE `brand` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(50)  NOT NULL COMMENT '品牌名称（唯一）',
    `logo`        VARCHAR(255) DEFAULT NULL COMMENT '品牌 logo URL',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_brand_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='品牌表';

CREATE TABLE `product_attribute` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `category_id` BIGINT      NOT NULL COMMENT '所属分类 id',
    `name`        VARCHAR(50) NOT NULL COMMENT '属性名称，如 颜色 / 尺寸',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_pa_category_id` (`category_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品属性 / 规格定义表';

CREATE TABLE `product_attribute_value` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `attribute_id` BIGINT      NOT NULL COMMENT '所属属性 id',
    `attr_value`   VARCHAR(50) NOT NULL COMMENT '可选值',
    `sort`         INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`   TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_pav_attribute_id` (`attribute_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品属性可选值表';

CREATE TABLE `sku_spec_value` (
    `id`                 BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sku_id`             BIGINT   NOT NULL COMMENT 'SKU id',
    `attribute_id`       BIGINT   NOT NULL COMMENT '属性 id',
    `attribute_value_id` BIGINT   NOT NULL COMMENT '属性值 id',
    `create_time`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ssv_sku_attribute` (`sku_id`, `attribute_id`),
    KEY `idx_ssv_attribute_value_id` (`attribute_value_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='SKU 规格值关联表';

CREATE TABLE `inventory` (
    `id`           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sku_id`       BIGINT   NOT NULL COMMENT 'SKU id（唯一）',
    `stock`        INT      NOT NULL DEFAULT 0 COMMENT '在库实物量',
    `locked_stock` INT      NOT NULL DEFAULT 0 COMMENT '未付款订单锁定中',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`   TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`      INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inventory_sku_id` (`sku_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存表';

CREATE TABLE `inventory_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sku_id`        BIGINT       NOT NULL COMMENT 'SKU id',
    `product_id`    BIGINT       NOT NULL COMMENT '商品 id（冗余，便于按商品维度查）',
    `change_type`   TINYINT      NOT NULL COMMENT '1 下单锁定 / 2 支付出库 / 3 取消释放 / 4 后台调整 / 5 导入初始化',
    `stock_before`  INT          NOT NULL COMMENT '变更前在库实物量',
    `stock_after`   INT          NOT NULL COMMENT '变更后在库实物量',
    `locked_before` INT          NOT NULL COMMENT '变更前锁定量',
    `locked_after`  INT          NOT NULL COMMENT '变更后锁定量',
    `biz_no`        VARCHAR(64)  DEFAULT NULL COMMENT '关联业务单号（订单号等）',
    `operator_id`   BIGINT       DEFAULT NULL COMMENT '操作人（后台调整时为管理员 id）',
    `remark`        VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_inventory_log_sku_time` (`sku_id`, `create_time`),
    KEY `idx_inventory_log_biz_no` (`biz_no`),
    KEY `idx_inventory_log_product_id` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存变更流水';

ALTER TABLE `product`
    ADD COLUMN `brand_id` BIGINT DEFAULT NULL COMMENT '品牌 id';
CREATE INDEX `idx_product_brand_id` ON `product` (`brand_id`);

INSERT INTO `inventory` (`sku_id`, `stock`, `locked_stock`)
SELECT `id`, `stock`, 0
FROM `product_sku`
WHERE `is_deleted` = 0;
