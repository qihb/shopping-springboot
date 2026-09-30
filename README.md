# spring-shop

一个生产级的电商网站后端系统，基于 Spring Boot 单体应用架构。包含完整的电商业务能力：用户、商品、购物车、订单与管理后台（RBAC 权限中心、操作审计）。

## 项目定位

- **生产可用**：覆盖电商核心业务链路（注册登录 → 浏览商品 → 加购 → 下单 → 支付/发货/收货），认证、鉴权、审计、限流加固等横切能力齐备。
- **工程规范**：统一的分层架构与编码规范，接口统一响应体、全局异常处理、Flyway 版本化迁移、单元测试与集成测试全覆盖、CI 自动构建。
- **可扩展**：采用 Maven 多模块架构，新增业务模块不影响既有代码，具备向微服务演进的清晰边界。

## 技术架构

| 层次 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | |
| 框架 | Spring Boot 3.5.16 | 自动化配置、内嵌容器 |
| 构建 | Maven | 多模块管理 |
| 持久层 | MyBatis-Plus 3.5.17 | 通用 CRUD、分页插件 |
| 数据库 | MySQL 8 | |
| 安全认证 | Spring Security + JWT | 前后台双链路隔离、RBAC 权限、BCrypt 密码加密 |
| 缓存 | Redis | 登录失败锁定、token 黑名单主动失效 |
| 数据库迁移 | Flyway | 版本化建表脚本 |
| 参数校验 | spring-boot-starter-validation | DTO 注解校验 |
| API 文档 | springdoc-openapi | 接口注解（@Tag / @Operation） |

### 模块划分

```
spring-shop
├── spring-shop-common   公共模块：统一响应(Result)、响应码枚举(ResultCode)、
│                        业务异常(BusinessException)、全局异常处理(GlobalExceptionHandler)、
│                        MyBatis-Plus 配置(分页插件)、JWT 工具(JwtTokenProvider)、
│                        Redis 配置(RedisConfig/RedisKeys)、通用分页入参(PageQuery)/分页结果(PageResult)
│                        ——禁止包含业务逻辑
├── spring-shop-user     用户模块：注册、登录（JWT 签发）、当前用户信息
├── spring-shop-admin    管理后台模块：管理员认证（失败锁定 + token 黑名单）、RBAC 权限中心
│                        （角色管理、菜单管理）、操作审计（AOP 落库）
├── spring-shop-product  商品模块：分类、SPU/SKU/图片，前后台列表与详情（读服务聚合）
├── spring-shop-cart     购物车模块：加购、改数量、勾选、删除与购物车列表（能力复用 product）
├── spring-shop-order    订单模块：收货地址、购物车勾选项下单（快照 + 扣库存）、
│                        订单状态流转（支付/取消/确认收货）、后台发货
└── spring-shop-web      启动模块：SpringBoot 启动类、控制器、Spring Security 双过滤链配置、配置文件
```

### 基础能力（已实现）

- **统一响应体**：所有接口返回 `Result<T>`（code / message / data），成功、失败响应规范统一。
- **全局异常处理**：业务异常、参数校验异常、类型转换异常、未知异常统一兜底，前端无需关心错误细节。
- **响应码枚举**：`ResultCode` 统一管理状态码，杜绝魔法数字。
- **MyBatis-Plus 集成**：分页插件、下划线转驼峰映射、主键自增、逻辑删除、乐观锁、字段自动填充（create_time / update_time / is_deleted / version）已就绪。
- **通用分页**：`PageQuery`（页码 + 每页条数）与 `PageResult<T>`（列表 + 总数 + 总页数），各模块分页接口复用。
- **用户与认证**：注册（用户名唯一、BCrypt 加密）、登录（校验通过签发 JWT）、`GET /api/user/me` 获取当前登录用户。
- **Spring Security 集成**：前后台双过滤链隔离（`/api/admin/**` 与前台互不越权），JWT 认证过滤器区分 ADMIN / USER 身份，除白名单接口外一律要求登录，当前用户 id 通过 `UserContext` 获取。
- **管理后台与权限中心（RBAC）**：管理员登录（连续失败 5 次锁定 15 分钟、退出登录 token 进 Redis 黑名单主动失效）、角色管理、菜单管理（菜单即权限标识），支持 `@PreAuthorize` 按钮级鉴权；初始超级管理员 `admin / admin123` 启动时自动创建。
- **操作审计**：`@OperationLog` 注解 + AOP 切面，自动记录后台关键操作的模块、操作类型、参数（密码脱敏）、IP、耗时并落库。
- **数据库迁移**：Flyway 版本化脚本管理表结构（V1 用户 / V2 商品 / V3 订单与购物车 / V4 管理后台与权限）。
- **测试与 CI**：JUnit 5 + Mockito 单元测试、MockMvc + H2 集成测试（不依赖本地 MySQL）、GitHub Actions 自动构建。
- **健康检查接口**：`GET /api/health` 用于验证服务是否正常启动。

## 业务模块

覆盖电商核心场景的以下业务模块：

| 模块 | 内容 | 状态 |
|------|------|------|
| 用户模块 | 注册、登录、个人中心 | ✅ 已完成 |
| 管理后台 | 管理员认证（失败锁定 + 黑名单）、RBAC 权限中心、角色/菜单管理、操作审计 | ✅ 已完成 |
| 商品模块 | 分类、商品列表、商品详情（SPU/SKU） | ✅ 已完成 |
| 购物车模块 | 加购、修改数量、勾选结算、清空 | ✅ 已完成 |
| 订单模块 | 收货地址、下单、订单状态流转 | ✅ 已完成 |

> 数据库表结构已通过 Flyway 迁移脚本完成设计（V1~V4），含逻辑删除、乐观锁、订单快照等电商通用设计。

## 项目启动

### 环境要求

- JDK 21
- Maven 3.6+
- MySQL 8（本地运行）
- Redis 6+（本地运行，管理后台登录锁定 / token 黑名单依赖）

### 1. 准备数据库

```sql
-- 创建数据库（字符集 utf8mb4 可支持完整中文与 emoji）
CREATE DATABASE spring_shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 配置数据库连接

编辑 [application.yml](spring-shop-web/src/main/resources/application.yml)，默认连接：

```
url: jdbc:mysql://localhost:3306/spring_shop
username: root
password: 通过环境变量 MYSQL_PASSWORD 指定，未设置时默认 root
```

```bash
# 若本机 MySQL 密码不是 root，启动前设置环境变量
export MYSQL_PASSWORD=你的密码
```

### 3. 编译 & 启动

```bash
# 编译整个项目
mvn clean compile

# 启动 web 模块
mvn -pl spring-shop-web -am spring-boot:run
```

### 4. 验证

```bash
curl http://localhost:6001/api/health
# 期望返回：
# {"code":200,"message":"操作成功","data":"spring-shop service is running"}
```

## 测试账号

### 管理后台

启动时自动创建超级管理员（数据库 admin_user 表为空时）：

```bash
# 管理员登录（默认：admin / admin123，上线后务必修改密码）
curl -X POST http://localhost:6001/api/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'

# 携带 token 访问后台接口（如角色分页）
curl http://localhost:6001/api/admin/roles \
  -H 'Authorization: Bearer <上一步返回的 token>'
```

### 前台用户

用户模块已上线，**调用注册接口创建账号**后即可登录：

```bash
# 注册
curl -X POST http://localhost:6001/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456","nickname":"测试用户"}'

# 登录（返回 JWT token 与用户信息）
curl -X POST http://localhost:6001/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"123456"}'

# 携带 token 访问受保护接口
curl http://localhost:6001/api/user/me \
  -H 'Authorization: Bearer <上一步返回的 token>'
```

> 提示：当前仅使用本机开发数据库，连接账号（`root`）仅用于本地调试，请勿在生产环境复用。

## 相关文档

- [AGENTS.md](AGENTS.md) — 项目协作规范（分层架构、编码规范、提交约定）
- [docs/user-module.md](docs/user-module.md) — 用户模块技术梳理（登录逻辑、认证链路、流程图）
- [docs/admin-module.md](docs/admin-module.md) — 管理后台技术梳理（双过滤链、RBAC、Redis 登录加固、操作审计、架构取舍）
- [docs/product-module.md](docs/product-module.md) — 商品模块技术梳理（SPU/SKU 模型、读写服务、分类树、测试基座）
- [docs/cart-module.md](docs/cart-module.md) — 购物车模块技术梳理（物理删除决策、N+1 聚合、失效标记、测试基座）
- [docs/order-module.md](docs/order-module.md) — 订单模块技术梳理（下单时序、库存条件扣减、快照设计、订单状态机）
- [docs/collaboration-plan.md](docs/collaboration-plan.md) — 基础框架评估与多人协作方案
