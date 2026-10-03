-- 测试环境用迁移脚本 V7（与主脚本 db/migration/V7__cart_recall.sql 内容一致）
-- 注意：H2 中索引名是库级唯一。本脚本新增的索引名已与既有索引全集比对无重名：
--   uk_recall_user_sku / idx_recall_stat_status / idx_recall_product
--   uk_recall_prod_date_rank / idx_recall_prod_date_pid
--   idx_stats_task_log_name_time / idx_cart_item_create_time
-- 因此无需重命名，与主脚本保持一致即可。

CREATE TABLE `cart_recall_target` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          BIGINT        NOT NULL COMMENT '用户 id',
    `sku_id`           BIGINT        NOT NULL COMMENT 'SKU id',
    `product_id`       BIGINT        NOT NULL COMMENT '商品 id',
    `product_name`     VARCHAR(100)  NOT NULL COMMENT '商品名称（快照）',
    `main_image`       VARCHAR(255)  DEFAULT NULL COMMENT '商品主图（快照）',
    `sku_price`        DECIMAL(10,2) NOT NULL COMMENT 'SKU 现价（快照）',
    `quantity`         INT           NOT NULL COMMENT '加购数量',
    `cart_amount`      DECIMAL(12,2) NOT NULL COMMENT '加购金额（元）= 现价 × 数量',
    `add_time`         DATETIME      NOT NULL COMMENT '首次加购时间（cart_item.create_time）',
    `idle_hours`       INT           NOT NULL COMMENT '已闲置小时数（跑批时刻 - 加购时间）',
    `user_phone`       VARCHAR(20)   DEFAULT NULL COMMENT '用户手机号（快照，供导出触达名单）',
    `user_openid`      VARCHAR(64)   DEFAULT NULL COMMENT '用户小程序 openid（快照，供订阅消息触达）',
    `reachable`        TINYINT       NOT NULL DEFAULT 0 COMMENT '是否可触达：1 有手机号或 openid / 0 均无',
    `suggested_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '建议券面额（元）',
    `status`           TINYINT       NOT NULL DEFAULT 0 COMMENT '处理状态：0 待处理 / 1 已发券 / 2 已转化 / 3 已失效',
    `stat_date`        DATE          NOT NULL COMMENT '最近一次跑批日',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_user_sku` (`user_id`, `sku_id`),
    KEY `idx_recall_stat_status` (`stat_date`, `status`),
    KEY `idx_recall_product` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='加购未买待召回人群池';

CREATE TABLE `cart_recall_product` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stat_date`        DATE          NOT NULL COMMENT '统计日期',
    `rank_no`          INT           NOT NULL COMMENT '名次，从 1 开始',
    `product_id`       BIGINT        NOT NULL COMMENT '商品 id（SPU）',
    `product_name`     VARCHAR(100)  NOT NULL COMMENT '商品名称（快照）',
    `abandon_user_cnt` INT           NOT NULL DEFAULT 0 COMMENT '加购未买用户数',
    `abandon_item_cnt` INT           NOT NULL DEFAULT 0 COMMENT '加购未买条目数',
    `abandon_amount`   DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '加购未买金额（元）',
    `paid_user_cnt`    INT           NOT NULL DEFAULT 0 COMMENT '已成交用户数（近 N 天，排除已取消）',
    `abandon_rate`     DECIMAL(6,4)  NOT NULL DEFAULT 0 COMMENT '弃购率',
    `avg_idle_hours`   INT           NOT NULL DEFAULT 0 COMMENT '平均闲置小时数',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_recall_prod_date_rank` (`stat_date`, `rank_no`),
    KEY `idx_recall_prod_date_pid` (`stat_date`, `product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='加购未买选品结果表';

CREATE TABLE `stats_task_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_name`   VARCHAR(64)  NOT NULL COMMENT '任务名',
    `stat_date`   DATE         NOT NULL COMMENT '业务日期',
    `status`      TINYINT      NOT NULL COMMENT '执行结果：1 成功 / 0 失败',
    `start_time`  DATETIME     NOT NULL COMMENT '开始时间',
    `end_time`    DATETIME     DEFAULT NULL COMMENT '结束时间',
    `duration_ms` BIGINT       DEFAULT NULL COMMENT '耗时（毫秒）',
    `row_count`   INT          DEFAULT NULL COMMENT '产出/处理行数',
    `error_msg`   VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_stats_task_log_name_time` (`task_name`, `start_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='统计任务执行日志';

CREATE INDEX `idx_cart_item_create_time` ON `cart_item` (`create_time`);
