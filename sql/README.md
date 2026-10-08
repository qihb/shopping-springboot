# 数据库脚本目录

本目录与 `spring-shop-web` 模块的 `db/migration` 目录配合，统一管理数据库脚本，与代码同步提交到 Git，保证环境可复现。

## 目录职责

| 位置 | 用途 |
|------|------|
| `sql/db-init.sql` | 建库脚本（每个新环境先执行一次，创建 `spring_shop` 库） |
| `sql/demo/gen_recall_demo.py` | 加购未买召回的**仿真数据集**生成器：重建隔离库 `spring_shop_demo`，复用真实迁移脚本建表，跑与 Java 完全相同的聚合 SQL 并输出报告（不碰主库 `spring_shop`） |
| `spring-shop-web/src/main/resources/db/migration/` | **Flyway 版本化迁移脚本**（建表 / 改表，应用启动时自动执行） |
| `spring-shop-web/src/test/resources/db/migration-test/` | 测试专用迁移副本（H2 兼容，索引名全局唯一化），**与主脚本保持同步** |

## Flyway 迁移规范

表结构统一交给 Flyway 管理（应用启动时自动执行未应用的脚本），不再手动执行建表 SQL。

### 新增迁移步骤

1. 在 `spring-shop-web/src/main/resources/db/migration/` 下新增脚本，命名 `V{n}__{描述}.sql`（`n` 为下一个版本号，描述用英文小写下划线）。
2. 脚本内按当前结构执行 `CREATE TABLE` / `ALTER TABLE`，**不要使用 `IF NOT EXISTS`**（Flyway 保证只执行一次，`IF NOT EXISTS` 会掩盖结构漂移）。
3. 启动应用验证迁移是否执行：`mvn clean package` 后 `java -jar spring-shop-web/target/spring-shop-web-1.0.0.jar`，执行记录写入 `flyway_schema_history` 表。
   > ⚠️ 不要用 `mvn -pl spring-shop-web -am spring-boot:run` —— `-am` 会把父聚合 POM 拉进反应堆，
   > 普通 goal 会先在它上面执行并报 `Unable to find a suitable main class`。详见 [README.md](../README.md#3-编译--启动)。
4. 新增带非主键索引的表时，**同步更新** `spring-shop-web/src/test/resources/db/migration-test/` 下的同名脚本，并确保索引名全局唯一（H2 索引名是库级唯一，重名会让 CI 直接挂）。

### 已有库的兼容说明

早期手动执行的库没有 `flyway_schema_history` 表，配置中已开启 `baseline-on-migrate: true` + `baseline-version: 0`，首次启动会以版本 0 为基线，再**按序执行从 V1 开始的全部迁移脚本**（当前 V1~V8）。其中 **V1~V3 保留了 `IF NOT EXISTS`**，用于安全跳过历史手建库里已存在的表；**V4 起的新脚本不要再带 `IF NOT EXISTS`**。

## 表结构规范

- 表名、字段名使用 `snake_case`，主键统一 `id BIGINT AUTO_INCREMENT`。
- 通用字段：`create_time DATETIME`、`update_time DATETIME`（由 MyBatis-Plus 自动填充）。
- 支持逻辑删除的表增加 `is_deleted TINYINT DEFAULT 0`（0 未删除 / 1 已删除）。
- 需要并发更新的表增加 `version INT DEFAULT 0`（乐观锁字段）。
