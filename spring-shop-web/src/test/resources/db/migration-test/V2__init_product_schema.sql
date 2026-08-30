-- 测试环境用迁移脚本 V2（与主脚本 db/migration/V2__init_product_schema.sql 内容一致）
-- 注意：H2 中索引名是库级唯一，主脚本里跨表重名的索引在此修正：
--   product_sku.idx_product_id  → idx_sku_product_id
--   product_image.idx_product_id → idx_image_product_id
CREATE TABLE IF NOT EXISTS `product_category` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类 id，0 表示顶级分类',
    `name`        VARCHAR(50) NOT NULL COMMENT '分类名称',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品分类表';

CREATE TABLE IF NOT EXISTS `product` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `category_id` BIGINT       NOT NULL COMMENT '所属分类 id',
    `name`        VARCHAR(100) NOT NULL COMMENT '商品名称',
    `subtitle`    VARCHAR(200) DEFAULT NULL COMMENT '副标题 / 卖点',
    `main_image`  VARCHAR(255) DEFAULT NULL COMMENT '主图 URL',
    `detail`      TEXT         COMMENT '商品详情（富文本 / HTML）',
    `sales`       INT          NOT NULL DEFAULT 0 COMMENT '累计销量',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '上架状态：1 上架 / 0 下架',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品表（SPU）';

CREATE TABLE IF NOT EXISTS `product_sku` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `product_id`     BIGINT        NOT NULL COMMENT '所属商品 id',
    `sku_code`       VARCHAR(64)   NOT NULL COMMENT 'SKU 编码（唯一）',
    `specs`          VARCHAR(255)  DEFAULT NULL COMMENT '规格描述，如 颜色:黑;尺寸:L',
    `price`          DECIMAL(10,2) NOT NULL COMMENT '销售价（元）',
    `original_price` DECIMAL(10,2) DEFAULT NULL COMMENT '划线价 / 原价（元）',
    `stock`          INT           NOT NULL DEFAULT 0 COMMENT '库存数量',
    `status`         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku_code` (`sku_code`),
    KEY `idx_sku_product_id` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品 SKU 表';

CREATE TABLE IF NOT EXISTS `product_image` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `product_id`  BIGINT       NOT NULL COMMENT '所属商品 id',
    `image_url`   VARCHAR(255) NOT NULL COMMENT '图片 URL',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_image_product_id` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品图片表';
