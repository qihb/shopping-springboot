-- Flyway 版本化迁移 V9：向「目标数据模型」对齐（规格体系 + 库存独立表 + 品牌）
-- 设计要点：
-- 1. 规格体系：product_attribute（属性定义）→ product_attribute_value（可选值）→ sku_spec_value（SKU 关联）；
--    product_sku.specs 保留为「冗余展示字段」，与关系表并存（对应「规格关系表加 JSON 冗余」）。
-- 2. 库存独立表：inventory（sku_id 唯一）+ inventory_log（append-only 流水）；
--    存量 product_sku.stock 一次性迁移进 inventory；product_sku.stock 暂留为迁移期镜像，
--    待业务写入路径全部切到 inventory 后，由后续版本移除（本轮不动它，保证零破坏）。
-- 3. 品牌：brand 基础资料 + product.brand_id。
-- 4. 索引名一律前缀化、全局唯一，与 migration-test 副本保持一致。
-- 说明：新脚本不带 IF NOT EXISTS（仅 V1~V3 兼容历史手建库）。

-- 品牌表（基础资料）
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

-- 商品属性 / 规格定义表（挂在分类下，如「颜色」「尺寸」）
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

-- 商品属性可选值表（受控值，如 黑 / 白 / L / XL）
-- 列名用 attr_value 而非 value：VALUE 是 H2 关键字，避免测试库建表失败。
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

-- SKU 规格值关联表（SKU ↔ 属性值，多对多）
-- 纯关联表：物理删除，不带 is_deleted / version / update_time（与 admin_user_role / role_menu 一致）
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

-- 库存表（单仓，sku_id 唯一）
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

-- 库存变更流水（append-only：只插不改不删，故刻意不带 is_deleted / version / update_time）
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

-- 商品表增加品牌
ALTER TABLE `product`
    ADD COLUMN `brand_id` BIGINT DEFAULT NULL COMMENT '品牌 id' AFTER `category_id`,
    ADD KEY `idx_product_brand_id` (`brand_id`);

-- 存量数据迁移：product_sku.stock → inventory（仅未删除 SKU）
-- product_sku.stock 暂留为迁移期镜像，业务改造完成后由后续版本移除。
INSERT INTO `inventory` (`sku_id`, `stock`, `locked_stock`)
SELECT `id`, `stock`, 0
FROM `product_sku`
WHERE `is_deleted` = 0;
