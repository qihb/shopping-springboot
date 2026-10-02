-- 测试环境用迁移脚本 V6（与主脚本 db/migration/V6__user_miniapp.sql 对应）
-- H2 兼容：使用标准 ADD CONSTRAINT ... UNIQUE 语法替代 MySQL 的 ADD UNIQUE KEY
ALTER TABLE `user`
    ADD COLUMN `openid` VARCHAR(64) DEFAULT NULL COMMENT '微信小程序 openid（多端登录标识）';
ALTER TABLE `user`
    ADD CONSTRAINT `uk_user_openid` UNIQUE (`openid`);
