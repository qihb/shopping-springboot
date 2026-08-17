# 数据库脚本目录

本目录统一管理数据库脚本，按业务模块分文件维护，与代码同步提交到 Git，保证环境可复现。

## 文件约定

| 文件 | 用途 |
|------|------|
| `db-init.sql` | 建库脚本（每个新环境先执行一次） |
| `{module}_schema.sql` | 各业务模块建表脚本，如 `user_schema.sql`、`order_schema.sql` |
| `{module}_data.sql` | 各业务模块初始化/演示数据（可选） |

## 表结构规范

- 表名、字段名使用 `snake_case`，主键统一 `id BIGINT AUTO_INCREMENT`。
- 通用字段：`create_time DATETIME`、`update_time DATETIME`（由 MyBatis-Plus 自动填充）。
- 支持逻辑删除的表增加 `is_deleted TINYINT DEFAULT 0`（0 未删除 / 1 已删除）。
- 需要并发更新的表增加 `version INT DEFAULT 0`（乐观锁字段）。

## 执行方式

手动执行（学习期）：每个业务模块开发时，将对应 `{module}_schema.sql` 在本地 MySQL 中执行。

> 后续业务表数量增多后，可引入 Flyway 进行版本化管理（脚本带版本号自动执行）。
