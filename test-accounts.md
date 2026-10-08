# 测试账号与本地环境信息备份

> ⚠️ 本文件仅记录**本地开发/测试环境**的账号信息，所有密码均为弱密码，禁止在任何真实环境使用。

## 一、本地数据库连接（MySQL 8）

| 项 | 值 |
|------|------|
| 地址 | `127.0.0.1:3306` |
| 数据库 | `spring_shop` |
| 用户名 | `root` |
| 密码 | `root`（应用侧通过环境变量 `MYSQL_PASSWORD` 覆盖，未设置时默认 root） |
| 字符集 | `utf8mb4` |

连接命令：

```bash
mysql -h127.0.0.1 -P3306 -uroot -proot --default-character-set=utf8mb4 spring_shop
```

## 二、管理后台账号

启动时由 `AdminDataInitializer` 幂等补齐（缺什么补什么，已存在的不覆盖、不重复插入）：

| 用户名 | 密码 | 角色 |
|------|------|------|
| `admin` | `admin123` | 超级管理员（拥有全部 RBAC 权限） |

登录地址：`POST http://localhost:6001/api/admin/auth/login`

## 三、前台用户账号（seed.sql 造的数据）

密码统一为 `123456`，由项目根目录 `seed.sql` 写入（密码为 BCrypt 加密存储）：

| 用户名 | 密码 | 昵称 | 手机号 | 说明 |
|------|------|------|------|------|
| `zhangsan` | `123456` | 张三 | 13800000001 | 有购物车、地址、待发货订单（SO20260930000001）、已完成订单 |
| `lisi` | `123456` | 李四 | 13800000002 | 有购物车、地址、待付款订单（SO20260930000002） |
| `wangwu` | `123456` | 王五 | 13800000003 | 仅地址，由 seed.sql 新增 |

登录地址：`POST http://localhost:6001/api/auth/login`

## 四、测试数据一览（seed.sql）

执行脚本（重复执行前先执行脚本末尾的清理语句）：

```bash
mysql -h127.0.0.1 -P3306 -uroot -proot --default-character-set=utf8mb4 spring_shop < seed.sql
```

| 表 | 数量 | 内容 |
|------|------|------|
| `product_category` | 10 | 手机数码 / 电脑办公 / 家用电器 / 食品生鲜 + 二级分类 |
| `product` | 10 | 手机、耳机、手表、笔记本、鼠标、键盘、电饭煲、牙刷、坚果、面包 |
| `product_sku` | 14 | 每个商品 1~3 个规格（颜色/版本/轴体等） |
| `product_image` | 6 | 商品详情图集（picsum 占位图） |
| `shipping_address` | 3 | 每个用户一条默认收货地址 |
| `cart_item` | 3 | zhangsan 2 条、lisi 1 条 |
| `orders` / `order_item` | 3 / 4 | 待付款、待发货、已完成各一笔（含快照明细） |

## 五、Swagger 与常用入口

| 入口 | 地址 |
|------|------|
| Swagger UI | http://localhost:6001/swagger-ui.html |
| OpenAPI JSON | http://localhost:6001/v3/api-docs |
| 健康检查（业务） | http://localhost:6001/api/health |
| 健康检查（Actuator） | http://localhost:6001/actuator/health |
| 指标（Prometheus） | http://localhost:6001/actuator/prometheus |
| 后台任务中心（Excel 异步任务） | `GET http://localhost:6001/api/admin/excel-tasks`（需管理员 token，只看自己的任务） |

> Actuator 只暴露 `health,info,metrics,prometheus`，且 `show-details: never`（生产安全默认）。
