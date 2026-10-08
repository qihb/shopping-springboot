# spring-shop

一个生产级的电商网站后端系统，基于 Spring Boot 单体应用架构。包含完整的电商业务能力：用户、商品、购物车、订单、支付、管理后台（RBAC 权限中心、操作审计）与数据运营（加购未买召回）。

## 项目定位

- **生产可用**：覆盖电商核心业务链路（注册登录 → 浏览商品 → 加购 → 下单 → 支付/发货/收货），认证、鉴权、审计、缓存、定时任务、可观测性等横切能力齐备。
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
| 缓存 | Redis | 登录失败锁定、token 黑名单主动失效、分类树/商品详情/购物车读加速、跑批任务锁 |
| 数据库迁移 | Flyway | 版本化建表脚本（V1~V8） |
| 参数校验 | spring-boot-starter-validation | DTO 注解校验 |
| API 文档 | springdoc-openapi | 接口注解（@Tag / @Operation / @Schema），Swagger UI 带 JWT Authorize |
| Excel | Fesod（Apache 孵化版 EasyExcel） | 流式读写，仅在 common 封装；统一异步导入导出任务框架 |
| 可观测性 | Spring Boot Actuator + Micrometer | 指标端点 + traceId 贯穿日志 |

### 模块划分

```
spring-shop
├── spring-shop-common   公共模块：统一响应(Result)、响应码枚举(ResultCode)、
│                        业务异常(BusinessException)、全局异常处理(GlobalExceptionHandler)、
│                        MyBatis-Plus 配置(分页插件)、JWT 工具(JwtTokenProvider)、
│                        Redis 配置(RedisConfig/RedisKeys)、通用分页入参(PageQuery)/分页结果(PageResult)
│                        Excel 能力（Fesod 封装 ExcelSupport/ExcelStreamWriter/ExcelExportSupport）
│                        与通用异步导入导出任务框架(excel/task：任务表、线程池、文件存取、进度与失败明细)
│                        ——禁止包含业务逻辑
├── spring-shop-user     用户模块：注册、登录（JWT 签发）、小程序登录、多端标识、当前用户信息
├── spring-shop-admin    管理后台模块：管理员认证（失败锁定 + token 黑名单）、RBAC 权限中心
│                        （角色管理、菜单管理）、操作审计（AOP 落库）、管理员账号管理、操作日志查询
├── spring-shop-product  商品模块：分类、SPU/SKU/图片，前后台列表与详情（读服务聚合）、库存条件扣减
├── spring-shop-cart     购物车模块：加购、改数量、勾选、删除与购物车列表（能力复用 product，Redis 读加速）
├── spring-shop-order    订单模块：收货地址、购物车勾选项下单（快照 + 扣库存）、
│                        订单状态流转（支付/取消/确认收货）、后台发货、待付款超时自动取消
├── spring-shop-pay      支付模块：模拟支付、支付记录流水（幂等 + 条件更新防并发）
├── spring-shop-stats    数据运营模块：加购未买召回圈人、选品分析、跑批任务日志
└── spring-shop-web      启动模块：SpringBoot 启动类、控制器（健康检查/任务中心）、
                         Spring Security 双过滤链配置、traceId 过滤器、配置文件与 Flyway 迁移脚本
```

### 基础能力（已实现）

- **统一响应体**：所有接口返回 `Result<T>`（code / message / data），成功、失败响应规范统一。
- **全局异常处理**：业务异常、参数校验异常、类型转换异常、未知异常统一兜底，前端无需关心错误细节。
- **响应码枚举**：`ResultCode` 统一管理状态码，杜绝魔法数字。
- **MyBatis-Plus 集成**：分页插件、下划线转驼峰映射、主键自增、逻辑删除、乐观锁、字段自动填充（create_time / update_time / is_deleted / version）已就绪。
- **通用分页**：`PageQuery`（页码 + 每页条数）与 `PageResult<T>`（列表 + 总数 + 总页数），各模块分页接口复用。
- **用户与认证**：注册（用户名唯一、BCrypt 加密）、登录（校验通过签发 JWT）、`GET /api/user/me` 获取当前登录用户。
- **Spring Security 集成**：前后台双过滤链隔离（`/api/admin/**` 与前台互不越权），JWT 认证过滤器区分 ADMIN / USER 身份，除白名单接口外一律要求登录，当前用户 id 通过 `UserContext` 获取。
- **管理后台与权限中心（RBAC）**：管理员登录（连续失败 5 次锁定 15 分钟、退出登录 token 进 Redis 黑名单主动失效）、**管理员账号管理**（增改 / 启停 / 重置密码 / 分配角色，用停用替代删除）、角色管理、菜单管理（菜单即权限标识）、**操作日志查询**，支持 `@PreAuthorize` 按钮级鉴权；初始超级管理员 `admin / admin123` 启动时自动创建。禁用或改权限在**下一次请求即生效**，无需等 token 过期。
- **操作审计**：`@OperationLog` 注解 + AOP 切面，自动记录后台关键操作的模块、操作类型、参数（含 `*password*` 字段脱敏）、IP、耗时并落库。
- **Excel 批量导入导出**：商品与管理员支持模板下载 + 批量导入，四类数据（商品 / 管理员 / 操作日志 / 订单）支持批量导出，全部走 `spring-shop-common` 的**统一异步任务框架**（提交即返回 `taskNo`，后台线程池执行，前端轮询进度 + 下载结果文件）。导入采用**部分成功**策略（合法行落库，非法行逐行返回「Excel 行号 + 原因」）。Excel 能力以 Fesod 薄封装形式沉淀在 `spring-shop-common`，业务模块不直接依赖 Fesod / POI。
- **定时任务**：待付款订单超时自动取消（60 秒扫描，条件更新保证并发安全）、加购未买召回跑批（每日 4 点，Redis 锁 + 任务日志）、Excel 临时文件清理（每日 3:30）。
- **数据库迁移**：Flyway 版本化脚本管理表结构（V1 用户 / V2 商品 / V3 订单与购物车 / V4 管理后台与权限 / V5 支付 / V6 小程序 / V7 加购召回 / V8 Excel 异步任务）。
- **测试与 CI**：JUnit 5 + Mockito 单元测试、MockMvc + H2 集成测试（不依赖本地 MySQL）、GitHub Actions 自动构建。当前全量 `mvn test` 为 **287 个用例全绿**（common 17 / user 17 / admin 28 / product 40 / cart 38 / order 29 / pay 7 / stats 10 / web 101）。
- **健康检查与可观测性**：`GET /api/health` 验证服务是否正常启动；Actuator 暴露 `/actuator/health`、`/actuator/prometheus`，请求响应头回写 `X-Trace-Id` 并贯穿日志。

> HTTP 状态码约定：认证/授权类失败用真实状态码（未登录 401、无权限 403），
> 业务类失败统一走「HTTP 200 + 响应体里的业务码」，避免把可预期的业务分支污染成 HTTP 错误。

## 业务模块

覆盖电商核心场景的以下业务模块：

| 模块 | 内容 | 状态 |
|------|------|------|
| 用户模块 | 注册、登录（含小程序）、个人中心、多端标识 | ✅ 已完成 |
| 管理后台 | 管理员认证（失败锁定 + 黑名单）、RBAC 权限中心、角色/菜单管理、**管理员账号管理**、**操作日志查询**、操作审计 | ✅ 已完成 |
| 商品模块 | 分类、商品列表、商品详情（SPU/SKU）、**Excel 批量导入** | ✅ 已完成 |
| 购物车模块 | 加购、修改数量、勾选结算、清空 | ✅ 已完成 |
| 订单模块 | 收货地址、下单（快照 + 扣库存）、订单状态流转、后台发货 | ✅ 已完成 |
| 支付模块 | 模拟支付、支付记录流水（幂等 + 条件更新防并发） | ✅ 已完成 |
| 数据运营模块 | 加购未买召回圈人、选品分析、跑批任务日志 | ✅ 已完成 |

> 数据库表结构已通过 Flyway 迁移脚本完成设计（V1~V8），含逻辑删除、乐观锁、订单快照等电商通用设计。

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

# 打包（含测试；本地想跳过测试可加 -DskipTests）
mvn clean package

# 启动（推荐做法：先 package，再直接跑可执行 jar）
java -jar spring-shop-web/target/spring-shop-web-1.0.0.jar
```

#### ⚠️ 不要用 `mvn -pl spring-shop-web -am spring-boot:run`

`-am`（also-make）会把**父聚合 POM**（`spring-shop`，packaging = pom）也拉进反应堆，
而 `spring-boot:run` 是普通（非 aggregator）goal，会按反应堆顺序先在聚合 POM 上执行，
直接报 `Unable to find a suitable main class`。

> 可复现：`mvn -pl spring-shop-web -am validate` 会依次构建 **10 个**模块，
> 第 1 个就是 `spring-shop`（父 POM）—— 这就是 `spring-boot:run` 失败的根因。

若确实想用 `spring-boot:run`，**去掉 `-am`**：

```bash
# 前提：兄弟模块已 mvn install 到本地仓库，否则会报「程序包 com.springshop.xxx 不存在」
mvn -pl spring-shop-web spring-boot:run
```

#### 关于 profile（重要）

`spring.profiles.active` 默认是 **`dev`**（见 `application.yml` 的 `${SPRING_PROFILES_ACTIVE:dev}`），
生产用 `SPRING_PROFILES_ACTIVE=prod` 覆盖。

**运行时的 profile 只支持 `dev` 和 `prod`**，原因有两个：

1. **数据源只在 `application-dev.yml` / `application-prod.yml` 里定义**，
   `application.yml` 本身不含 `spring.datasource`。所以用别的 profile 启动会直接
   报 `Failed to determine a suitable driver class`。
2. **`logback-spring.xml` 里 `dev` / `prod` 各自定义 `<root>`**，另有 `!dev & !prod` 兜底分支。
   若删掉兜底分支，用其他 profile（例如打错成 `develop`）启动时根 logger 会一个 appender 都没有，
   应用**一行日志都不打印**，启动失败只剩一个退出码 1，无从排查。

> 注意：`test` profile 的数据源在 `spring-shop-web/src/test/resources/application-test.yml`（测试作用域，**不会打进 jar**），
> 因此它只用于 `mvn test`，**不能**用 `java -jar --spring.profiles.active=test` 启动。

### 4. 验证

```bash
curl http://localhost:6001/api/health
# 期望返回：
# {"code":200,"message":"操作成功","data":"spring-shop service is running"}
```

## 测试账号

### 管理后台

启动时**幂等补齐**初始数据（逐项「查不到就创建」，已存在的不会重复插入，也不会被覆盖）：
超级管理员 `admin / admin123`、`ADMIN` 角色、全部内置菜单与按钮权限，以及管理员与角色、
角色与菜单的关联。后续版本新增的权限标识重启后会自动写入已有库。

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
>
> 完整的测试账号、数据库连接信息与种子数据说明已备份至 [test-accounts.md](test-accounts.md)。

## 相关文档

- [AGENTS.md](AGENTS.md) — 项目协作规范（分层架构、编码规范、错误码段位、HTTP 状态码分工、测试模板、提交约定）
- [docs/user-module.md](docs/user-module.md) — 用户模块技术梳理（登录逻辑、认证链路、流程图）
- [docs/admin-module.md](docs/admin-module.md) — 管理后台技术梳理（双过滤链、RBAC、Redis 登录加固、操作审计、账号管理、日志查询、导入设计、架构取舍）
- [docs/product-module.md](docs/product-module.md) — 商品模块技术梳理（SPU/SKU 模型、读写服务、分类树、批量导入、测试基座）
- [docs/cart-module.md](docs/cart-module.md) — 购物车模块技术梳理（物理删除决策、N+1 聚合、失效标记、测试基座）
- [docs/order-module.md](docs/order-module.md) — 订单模块技术梳理（下单时序、库存条件扣减、快照设计、订单状态机）
- [docs/superpowers/plans/2026-10-03-admin-user-log-import.md](docs/superpowers/plans/2026-10-03-admin-user-log-import.md) — 后台账号管理 + 日志查询 + 商品/管理员导入 实施计划
- [docs/superpowers/plans/2026-10-02-cart-recall-coupon.md](docs/superpowers/plans/2026-10-02-cart-recall-coupon.md) — 加购未买召回发券技术方案（Phase 1 圈人已落地，Phase 2/3 待拍板）
- [docs/superpowers/plans/2026-10-03-cart-recall-phase1-results.md](docs/superpowers/plans/2026-10-03-cart-recall-phase1-results.md) — 召回 Phase 1 实测数据与触达可行性评估
- [docs/superpowers/plans/2026-10-02-roadmap-ops-enhance.md](docs/superpowers/plans/2026-10-02-roadmap-ops-enhance.md) — 架构增强路线图（超时取消 / 可观测性 / Docker / 购物车 Redis / 支付）
- [docs/superpowers/plans/2026-10-03-scheduled-task-platform-options.md](docs/superpowers/plans/2026-10-03-scheduled-task-platform-options.md) — 定时任务平台化 + 重跑机制方案（未落地，待拍板）
- [docs/superpowers/plans/2026-10-06-rest-to-mcp-evaluation.md](docs/superpowers/plans/2026-10-06-rest-to-mcp-evaluation.md) — REST→MCP 四方案实测存档（**已评估并否决，勿执行**）
- [docs/collaboration-plan.md](docs/collaboration-plan.md) — 基础框架评估与多人协作方案
- [test-accounts.md](test-accounts.md) — 测试账号与本地环境信息备份（数据库连接、前后台账号、seed 数据）
