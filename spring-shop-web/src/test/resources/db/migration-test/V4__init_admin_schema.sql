-- 测试环境用迁移脚本 V4（与主脚本 db/migration/V4__init_admin_schema.sql 内容一致）
-- 注意：H2 中索引名是库级唯一，本脚本索引名均带独立前缀，不与既有脚本冲突
CREATE TABLE `admin_user` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`        VARCHAR(50)  NOT NULL COMMENT '登录用户名（唯一）',
    `password`        VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密）',
    `real_name`       VARCHAR(50)  DEFAULT NULL COMMENT '真实姓名',
    `phone`           VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1 启用 / 0 禁用',
    `last_login_time` DATETIME     DEFAULT NULL COMMENT '最近登录时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='管理员表';

CREATE TABLE `role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(50)  NOT NULL COMMENT '角色名称',
    `code`        VARCHAR(50)  NOT NULL COMMENT '角色编码（唯一，如 ADMIN / OPERATOR）',
    `description` VARCHAR(200) DEFAULT NULL COMMENT '角色描述',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色表';

CREATE TABLE `menu` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单 id，0 表示顶级',
    `name`            VARCHAR(50)  NOT NULL COMMENT '菜单名称',
    `type`            TINYINT      NOT NULL COMMENT '类型：1 目录 / 2 菜单 / 3 按钮',
    `path`            VARCHAR(200) DEFAULT NULL COMMENT '前端路由路径',
    `permission_code` VARCHAR(100) DEFAULT NULL COMMENT '权限标识，如 product:sku:edit（按钮级权限用）',
    `icon`            VARCHAR(50)  DEFAULT NULL COMMENT '菜单图标',
    `sort`            INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_menu_parent_id` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='菜单 / 权限表';

CREATE TABLE `admin_user_role` (
    `id`            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `admin_user_id` BIGINT   NOT NULL COMMENT '管理员 id',
    `role_id`       BIGINT   NOT NULL COMMENT '角色 id',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_admin_user_role` (`admin_user_id`, `role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='管理员-角色关联表';

CREATE TABLE `role_menu` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_id`     BIGINT   NOT NULL COMMENT '角色 id',
    `menu_id`     BIGINT   NOT NULL COMMENT '菜单 id',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色-菜单关联表';

CREATE TABLE `operation_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `admin_user_id`  BIGINT       DEFAULT NULL COMMENT '操作管理员 id',
    `username`       VARCHAR(50)  DEFAULT NULL COMMENT '操作管理员用户名',
    `module`         VARCHAR(50)  DEFAULT NULL COMMENT '所属模块，如 商品管理',
    `operation`      VARCHAR(100) DEFAULT NULL COMMENT '操作描述，如 新增商品',
    `request_uri`    VARCHAR(200) DEFAULT NULL COMMENT '请求路径',
    `request_method` VARCHAR(10)  DEFAULT NULL COMMENT '请求方式 GET/POST/PUT/DELETE',
    `request_params` TEXT         COMMENT '请求参数（脱敏后记录）',
    `ip`             VARCHAR(50)  DEFAULT NULL COMMENT '操作人 IP',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '执行结果：1 成功 / 0 失败',
    `error_msg`      VARCHAR(500) DEFAULT NULL COMMENT '异常信息（失败时记录）',
    `duration_ms`    BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_op_log_admin_user_id` (`admin_user_id`),
    KEY `idx_op_log_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='操作日志表';
