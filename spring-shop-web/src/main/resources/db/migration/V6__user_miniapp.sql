-- Flyway 版本化迁移 V6：用户表增加微信小程序 openid（多端支持）
-- 设计要点：
-- 1. openid 是微信小程序用户在该小程序下的唯一标识，加唯一索引 uk_user_openid 防止重复绑定；
-- 2. 允许 NULL：仅用户名密码注册的用户不填 openid，MySQL / H2 的唯一索引均允许多个 NULL；
-- 3. 禁止修改已提交脚本，表结构变更一律新增 V{n} 脚本。
ALTER TABLE `user`
    ADD COLUMN `openid` VARCHAR(64) DEFAULT NULL COMMENT '微信小程序 openid（多端登录标识）' AFTER `phone`,
    ADD UNIQUE KEY `uk_user_openid` (`openid`);
