-- Flyway 版本化迁移 V7：统计分析模块建表（spring-shop-stats）
-- 设计要点：
-- 1. 本轮只做「圈人」（加购未买待召回人群圈选），不发券、不触达，因此不引入券相关表；
-- 2. cart_recall_target 是「当前待召回池」，每天跑批滚动刷新，不做按天快照
--    （按天快照会以「用户数 × 车内容量」的量级膨胀，且业务上只需要当前可触达的人群）；
-- 3. cart_recall_product 只存每日选品聚合结果，行数极少，可长期保留用于趋势对比；
-- 4. 索引名全局唯一：H2 中索引名是库级唯一，CI 会用 migration-test 副本跑迁移，命名需与既有索引无重名。

-- 加购未买待召回人群池（当前池，滚动刷新）
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

-- 每日选品结果（加购未买 TOP N 商品 + 弃购率）
-- 弃购率 = 加购未买用户数 / (加购未买用户数 + 已成交用户数)，衡量「想买但卡在价格上」的程度
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

-- 统计任务执行日志：定时任务失败被框架吞掉时，这是「今天为什么没出数据」的唯一排查入口
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

-- cart_item 补 create_time 索引：圈人按「加购时间窗口」过滤，当前该表只有 uk_user_sku(user_id, sku_id)，
-- 时间条件无索引可用，只能全表扫描。用 CREATE INDEX 而非 ALTER TABLE ... ADD KEY，
-- 以保证 MySQL 与 H2（测试库）语法通用。
-- 注：cart_item 为高频写入表，此处只加单列索引控制写放大；
--     若后续数据量极大，可评估升级为覆盖索引 (create_time, user_id, sku_id, quantity) 以消除回表。
CREATE INDEX `idx_cart_item_create_time` ON `cart_item` (`create_time`);
