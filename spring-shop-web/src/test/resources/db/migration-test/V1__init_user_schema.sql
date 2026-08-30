-- 测试环境用迁移脚本 V1（与主脚本 db/migration/V1__init_user_schema.sql 内容一致）
-- 注意：H2 中索引名是库级唯一，主脚本里跨表重名的索引在此修正
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名（唯一）',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密）',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态：1 正常 / 0 禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 / 1 已删除',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';
