-- 测试环境用迁移脚本 V10（与主脚本 db/migration/V10__drop_product_sku_stock.sql 对应）
--
-- H2 兼容：DROP COLUMN 不需要 MySQL 的 AFTER / KEY 之类子句，两副本内容可以完全一致。
-- 测试库每次都是全新空库，V2 建列 → V9 回填 → V10 删列，跑完与生产最终结构一致。

ALTER TABLE `product_sku` DROP COLUMN `stock`;
