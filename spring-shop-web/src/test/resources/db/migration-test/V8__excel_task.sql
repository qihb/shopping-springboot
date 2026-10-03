-- 测试环境用迁移脚本 V8（与主脚本 db/migration/V8__excel_task.sql 内容一致）
-- 注意：H2 中索引名是库级唯一。本脚本新增的索引名已与既有索引全集比对无重名：
--   uk_excel_task_no / idx_excel_task_creator / idx_excel_task_status / idx_excel_task_err_no
-- 因此无需重命名，与主脚本保持一致即可。

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
