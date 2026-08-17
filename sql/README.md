# 数据库脚本目录

本目录与 `spring-shop-web` 模块的 `db/migration` 目录配合，统一管理数据库脚本，与代码同步提交到 Git，保证环境可复现。

## 目录职责

| 位置 | 用途 |
|------|------|
| `sql/db-init.sql` | 建库脚本（每个新环境先执行一次，创建 `spring_shop` 库） |
| `spring-shop-web/src/main/resources/db/migration/` | **Flyway 版本化迁移脚本**（建表 / 改表，应用启动时自动执行） |

## Flyway 迁移规范

表结构统一交给 Flyway 管理（应用启动时自动执行未应用的脚本），不再手动执行建表 SQL。

### 新增迁移步骤

1. 在 `spring-shop-web/src/main/resources/db/migration/` 下新增脚本，命名 `V{n}__{描述}.sql`（`n` 为下一个版本号，描述用英文小写下划线）。
2. 脚本内按当前结构执行 `CREATE TABLE` / `ALTER TABLE`，**不要使用 `IF NOT EXISTS`**（Flyway 保证只执行一次，`IF NOT EXISTS` 会掩盖结构漂移）。
3. 启动应用验证：`mvn -pl spring-shop-web -am spring-boot:run`，执行记录写入 `flyway_schema_history` 表。

### 已有库的兼容说明

早期手动执行的库没有 `flyway_schema_history` 表，配置中已开启 `baseline-on-migrate: true` + `baseline-version: 0`，首次启动会以版本 0 为基线，再完整执行 V1~V3（这几个脚本保留了 `IF NOT EXISTS` 以安全跳过已存在的表）。**V4 起的新脚本不要再带 `IF NOT EXISTS`。**

## 表结构规范

- 表名、字段名使用 `snake_case`，主键统一 `id BIGINT AUTO_INCREMENT`。
- 通用字段：`create_time DATETIME`、`update_time DATETIME`（由 MyBatis-Plus 自动填充）。
- 支持逻辑删除的表增加 `is_deleted TINYINT DEFAULT 0`（0 未删除 / 1 已删除）。
- 需要并发更新的表增加 `version INT DEFAULT 0`（乐观锁字段）。
