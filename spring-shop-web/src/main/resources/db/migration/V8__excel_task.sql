-- Flyway 版本化迁移 V8：Excel 异步导入 / 导出任务（spring-shop-common 的 excel.task 框架）
-- 设计要点：
-- 1. 导入与导出共用一张 excel_task：两者都是「提交即返回 task_no → 后台线程执行 → 前端轮询进度 → 下载文件」，
--    差别只在 task_type（1 导入 / 2 导出）与文件流向，拆两张表会让任务查询接口与前端列表页各写一套；
-- 2. 任务状态落库而不是放 Redis：上万行导入要跑几十秒到几分钟，用户会关页面、刷新、甚至重启应用，
--    Redis（带 TTL）一重启就丢，任务就变成「永远转圈」；落库还能顺带满足「谁在什么时候导了什么」的审计诉求；
-- 3. 失败明细单独建表 excel_task_error，不用 JSON 列堆在 excel_task 上：
--    一个写错列的文件可能 1 万行全失败，JSON 列会膨胀到几百 KB，且无法按行号检索 / 分页；
--    明细表按 task_no 建索引，下载失败明细时按批流式写出，内存恒定；
-- 4. file_path 指向服务器本地临时文件（导入的原始文件 / 导出的结果文件），
--    由 excel.task.file-retain-hours 控制保留时长，到期由清理任务删除；
-- 5. 索引名全局唯一：H2 中索引名是库级唯一，CI 会用 migration-test 副本跑迁移，命名需与既有索引无重名。

-- Excel 异步任务（导入 + 导出）
CREATE TABLE `excel_task` (
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_no`        VARCHAR(40)   NOT NULL COMMENT '任务号，对外唯一标识（前端轮询与下载都用它）',
    `biz_type`       VARCHAR(40)   NOT NULL COMMENT '业务类型编码：PRODUCT_IMPORT / PRODUCT_EXPORT / ADMIN_USER_IMPORT …',
    `biz_name`       VARCHAR(64)   NOT NULL COMMENT '业务类型展示名：商品导入 / 商品导出 …（任务列表直接展示，避免前端硬编码编码表）',
    `task_type`      TINYINT       NOT NULL COMMENT '任务方向：1 导入 / 2 导出',
    `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '任务状态：0 待执行 / 1 执行中 / 2 成功 / 3 失败',
    `file_name`      VARCHAR(255)  DEFAULT NULL COMMENT '文件名：导入为上传的原始名，导出为生成的文件名',
    `file_path`      VARCHAR(500)  DEFAULT NULL COMMENT '临时文件绝对路径（导入源文件 / 导出结果文件）',
    `params`         TEXT          DEFAULT NULL COMMENT '导出查询条件 JSON（导入任务为空）',
    `total_rows`     INT           NOT NULL DEFAULT 0 COMMENT '总行数：导入为解析出的数据行数，导出为导出行数',
    `processed_rows` INT           NOT NULL DEFAULT 0 COMMENT '已处理行数（导入进度用，导出与 total_rows 同步）',
    `success_rows`   INT           NOT NULL DEFAULT 0 COMMENT '成功行数',
    `fail_rows`      INT           NOT NULL DEFAULT 0 COMMENT '失败行数',
    `error_msg`      VARCHAR(1000) DEFAULT NULL COMMENT '任务级失败原因（文件无法解析、执行异常等）',
    `created_by`     BIGINT        NOT NULL COMMENT '提交人（管理员 id）：任务列表与文件下载按此做归属校验',
    `start_time`     DATETIME      DEFAULT NULL COMMENT '开始执行时间',
    `end_time`       DATETIME      DEFAULT NULL COMMENT '结束时间',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_excel_task_no` (`task_no`),
    KEY `idx_excel_task_creator` (`created_by`, `create_time`),
    KEY `idx_excel_task_status` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Excel 异步导入导出任务';

-- 导入失败明细：一行一条原因，行号与用户在 Excel 里看到的行号一致（表头为第 1 行）
CREATE TABLE `excel_task_error` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_no`     VARCHAR(40)   NOT NULL COMMENT '所属任务号',
    `row_num`     INT           NOT NULL COMMENT '出错行号（与 Excel 界面显示一致）',
    `message`     VARCHAR(500)  NOT NULL COMMENT '失败原因',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_excel_task_err_no` (`task_no`, `row_num`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Excel 导入失败明细';
