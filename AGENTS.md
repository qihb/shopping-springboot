# AGENTS.md — spring-shop 项目协作规范

本文件面向在本仓库中工作的开发者与 AI 编码助手，用于统一协作方式与代码规范，保证代码风格一致、可维护。

## 项目概述

- **项目名称**：spring-shop
- **项目定位**：生产级 Java 电商网站后端，单体应用架构（可扩展）
- **基础框架**：Spring Boot 3.5.16 + Maven 多模块 + Java 21
- **持久层**：MyBatis-Plus 3.5.17 + MySQL 8
- **当前状态**：用户、商品、购物车、订单、支付、管理后台、数据运营七大业务模块已全部交付（注册/登录/JWT 认证、RBAC 权限中心、操作审计、下单库存扣减、模拟支付、加购未买召回圈人），测试基座与 CI 已就绪

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 语言 |
| Spring Boot | 3.5.16 | 框架 |
| Maven | - | 构建工具 |
| MyBatis-Plus | 3.5.17 | ORM / 持久层 |
| MySQL | 8.x | 数据库 |
| Redis | 6.x | 缓存（登录失败锁定、token 黑名单、分类树 / 商品详情 / 购物车读加速、任务锁） |
| Flyway | - | 版本化数据库迁移（当前 V1~V8） |
| Fesod（Apache 孵化版 EasyExcel） | `fesod-sheet` | Excel 流式读写（只在 common 封装，业务模块不直接依赖） |
| springdoc-openapi | - | 接口文档（Swagger UI + `@Tag` / `@Operation` / `@Schema`） |
| Spring Boot Actuator + Micrometer | - | 指标暴露（`/actuator/health`、`/actuator/prometheus`）与 traceId 贯穿 |
| spring-boot-starter-validation | - | 参数校验 |
| JUnit 5 + Mockito + H2 | - | 测试：单元测试 + 接口集成测试（H2 以 MySQL 模式跑迁移脚本） |

## 项目结构

```
spring_shop/
├── pom.xml                     # 父 POM：统一依赖管理
├── spring-shop-common/         # 公共模块：不依赖业务
│   └── src/main/java/com/springshop/common/
│       ├── config/             # 全局配置（MybatisPlus/Jackson/CORS/Redis）
│       ├── exception/          # 业务异常 + 全局异常处理
│       ├── result/             # 统一响应 Result / ResultCode / PageResult
│       ├── dto/                # 通用分页入参 PageQuery
│       ├── excel/              # 通用 Excel 读写（Fesod 封装：ExcelSupport / ExcelStreamWriter /
│       │                       #   ExcelExportSupport / ExcelRow / ExcelReadOptions / ExcelFileType）
│       │   └── task/           # 通用异步导入导出任务框架（任务表、线程池、文件存取、进度与失败明细）
│       └── security/           # JWT 工具 JwtTokenProvider、用户上下文 UserContext、RedisKeys
├── spring-shop-user/           # 用户模块：注册、登录（含小程序）、当前用户、多端标识
├── spring-shop-product/        # 商品模块：分类、SPU/SKU/图片，前后台列表与详情（读服务聚合）、库存条件扣减
├── spring-shop-cart/           # 购物车模块：加购、改数量、勾选、删除与购物车列表
├── spring-shop-order/          # 订单模块：收货地址、下单（快照 + 扣库存）、状态流转、后台发货
├── spring-shop-pay/            # 支付模块：模拟支付、支付记录（幂等 + 条件更新防并发）
├── spring-shop-admin/          # 管理后台模块：管理员认证、RBAC 权限中心、操作审计、管理员账号管理、操作日志查询
│   └── src/main/java/com/springshop/admin/
│       ├── aspect/             # 操作审计注解 + AOP 切面
│       ├── config/             # 初始数据初始化器 AdminDataInitializer（幂等 find-or-create）
│       ├── security/           # AdminJwtAuthenticationFilter / AdminUserPrincipal / AdminUserDetailsService
│       ├── controller/ service/ mapper/ entity/ dto/ vo/
│       └── pom.xml             # 依赖 common + validation + aop + spring-security-web
├── spring-shop-stats/          # 数据运营模块：加购未买召回圈人、选品分析、跑批任务日志
│   └── src/main/java/com/springshop/stats/
│       ├── config/             # RecallProperties（阈值全部配置化）
│       ├── controller/admin/   # AdminRecallController（/api/admin/stats/recall/**）
│       ├── entity/ mapper/ service/ task/ vo/
│       └── pom.xml             # 依赖 common + cart + product + order
└── spring-shop-web/            # 启动模块：依赖所有业务模块
    └── src/main/java/com/springshop/
        ├── SpringShopApplication.java  # 启动类（根包 com.springshop，扫描全部模块）
        ├── controller/         # 控制器层（健康检查）
        ├── security/           # SecurityConfig（双过滤链）/ JwtAuthenticationFilter
        └── resources/          # application.yml + db/migration 迁移脚本
```

### 模块职责

- **spring-shop-common**：跨模块共享的公共代码，**禁止出现业务逻辑**，只放通用能力（统一响应、异常、配置、工具类、通用枚举等）。
- **spring-shop-user**：用户业务模块（注册、登录、小程序登录、JWT 认证适配），依赖 common。
  多端支持：请求头 `X-Client-Id`（`WEB`/`MINIAPP`/`APP`，缺省 `WEB`）经 `ClientIdFilter` 写入 `ClientContext`，
  登录时把 clientId 写入 JWT 的 `clientId` claim；小程序登录 `POST /api/auth/miniapp/login` 用 code 换 openid，
  首次登录自动建号（`user.openid` 唯一）。
- **spring-shop-product**：商品业务模块（分类、SPU/SKU/图片、前后台商品读写、库存扣减），依赖 common。
- **spring-shop-cart**：购物车业务模块（加购、数量调整、勾选、删除），复用 product 的只读查询，依赖 common + product。
- **spring-shop-order**：订单业务模块（收货地址、下单快照、条件扣库存、状态流转、后台发货），依赖 common + product + cart。
- **spring-shop-pay**：支付业务模块（模拟支付、支付记录流水），复用 order 的订单查询与 markPaid 条件更新，依赖 common + order。
- **spring-shop-admin**：管理后台业务模块（管理员认证、RBAC 权限中心、操作审计），依赖 common。
- **spring-shop-stats**：数据运营模块（加购未买召回圈人、选品分析、跑批任务日志），
  只做只读统计与人群池落库，**不参与下单/支付金额链路**，依赖 common + cart + product + order。
  关键口径：`cart_item` 因下单成功即被物理删除，**其剩余行按定义就是「加购未买」集合**；
  `create_time` 只在首次加购时写入，是准确的「首次加购时刻」。
  跑批走 `@Scheduled(cron, zone)` + Redis 锁 + `stats_task_log`，阈值全部在 `stats.cart-recall.*` 配置。
- **spring-shop-web**：应用启动模块，含启动类、控制器、安全配置（前后台双过滤链）与配置文件，统一依赖所有业务模块。

### 新增业务模块约定

新增业务模块时（如 `spring-shop-pay`、`spring-shop-coupon`），应遵循：

1. 在父 POM 的 `<modules>` 中注册，并在 `<dependencyManagement>` 中声明版本。
2. 模块内部按 `controller / service / mapper / entity / dto / vo` 分层组织包。
3. 业务模块依赖 `spring-shop-common`；web 启动模块统一依赖所有业务模块。
4. 在 `ResultCode` 中申请**本模块专属的错误码段**（见「错误码段位」），禁止占用其他模块段位。

## 分层架构规范

所有业务代码遵循经典 MVC + Service 分层，包名统一为 `com.springshop.<module>.<layer>`：

| 层 | 包名 | 职责 | 说明 |
|----|------|------|------|
| 控制层 | `controller` | 接收请求、参数校验、调用 service | 只做参数接收与结果返回，不写业务逻辑 |
| 服务层 | `service` | 业务逻辑 | 接口 + impl 实现，事务在此层管理 |
| 持久层 | `mapper` | 数据库访问 | 继承 `BaseMapper<T>`，复杂 SQL 放 XML |
| 实体层 | `entity` | 数据库表映射 | 与表字段一一对应 |
| DTO | `dto` | 入参对象 | 请求体 / 查询参数 |
| VO | `vo` | 出参对象 | 响应给前端的数据 |

**分层红线**：
- Controller 不直接操作 Mapper。
- Entity 不直接返回给前端，对外统一使用 VO。
- 跨层传递使用 DTO/VO，不直接暴露数据库实体。

## 编码规范

### 类结构

类内成员按 **先声明字段，再写构造函数，最后是方法** 的顺序组织（项目约定，参考 `Result.java`）。

### 命名规范

- 包名：全小写，`com.springshop.<module>.<layer>`
- 类名：大驼峰（`OrderService`、`UserController`）
- 方法 / 变量：小驼峰
- 常量：全大写 + 下划线（如 `SYSTEM_ERROR`）
- 数据库表：`snake_case`，实体字段使用驼峰，由 MyBatis-Plus 自动映射（`map-underscore-to-camel-case: true`）

### 注释规范

- 类、公共方法使用 **中文 Javadoc**（`/** ... */`）说明用途。
- 复杂业务逻辑在关键步骤处添加行内注释（中文）。
- 不要为显而易见的自解释代码添加冗余注释。

### 统一响应

所有 Controller 接口必须返回 `Result<T>` 包装：

```java
@GetMapping("/health")
public Result<String> health() {
    return Result.success("ok");
}
```

- 成功：`Result.success(data)` / `Result.success()`
- 失败：抛出 `BusinessException`，由 `GlobalExceptionHandler` 统一处理，**禁止在 Controller 里直接 return Result.fail(...) 处理业务失败**。

### 异常处理

- 业务校验失败抛出 `BusinessException`，并指定合理的错误码。
- 需要新增响应码时，在 `ResultCode` 枚举中扩展，**不要使用魔法数字**。
- 全局兜底由 `GlobalExceptionHandler` 负责（参数校验、类型转换、未知异常）。

### HTTP 状态码与业务码的分工

项目同时使用「真实 HTTP 状态码」和「统一响应体里的业务码」，分工如下：

| 场景 | HTTP 状态 | 响应体 | 由谁负责 |
|------|-----------|--------|----------|
| 未认证 / token 无效 / 账号被禁用 | 401 | `Result.fail(ResultCode.UNAUTHORIZED)` | 过滤链 `AuthenticationEntryPoint` |
| 已认证但无权限（`@PreAuthorize` 拒绝） | 403 | `Result.fail(ResultCode.FORBIDDEN)` | `SecurityExceptionHandler`（web 模块） |
| 业务校验失败（库存不足、用户名已存在等） | 200 | `Result.fail(业务码, 原因)` | `GlobalExceptionHandler` |
| 上传文件超限 | 200 | `Result.fail(400, "上传文件过大…")` | `GlobalExceptionHandler` |

> ⚠️ 上传超限这一行**成立的前提是同时配置了 `server.tomcat.max-swallow-size`**（当前 12MB）。
> Tomcat 的 `maxSwallowSize` 默认只有 2MB，被拒绝的请求体超过 2MB 时 Tomcat 会直接断连，
> 客户端拿到的是 `Broken pipe`，这个友好提示**根本送不出去**。详见「测试编写与 CI 通过规范」第 2 条。

**认证/授权类失败必须用真实 HTTP 状态码**，因为前端（以及网关、监控）通常先按 HTTP 状态做统一拦截；
业务类失败才走「HTTP 200 + 业务码」，避免把可预期的业务分支记成 HTTP 错误、污染监控指标。

**为什么 `SecurityExceptionHandler` 放在 `spring-shop-web` 而不是 `spring-shop-common`**：
`@PreAuthorize` 抛出的 `AccessDeniedException` 发生在 Controller 调用期，此时请求已越过 Security
过滤链、不会经过 `ExceptionTranslationFilter`，只能由 `@RestControllerAdvice` 兜住；而
`spring-shop-common` 刻意不依赖 spring-security（保持公共模块轻量），所以这个 Advice 放在
web 模块，并用 `@Order(HIGHEST_PRECEDENCE)` 抢在 common 的通用兜底 Advice 之前匹配。

### 错误码段位

`ResultCode` 为全局共享枚举，多人并行开发时**按段位分配**，避免改同一文件冲突：

| 段位 | 归属 |
|------|------|
| 0~99 | 公共错误（参数/鉴权/系统） |
| 1000~1999 | 用户模块 |
| 2000~2999 | 商品模块 |
| 3000~3999 | 购物车模块 |
| 4000~4999 | 订单模块 |
| 5000~5999 | 管理后台模块 |
| 6000~6999 | 支付模块 |
| 7000~7999 | 数据运营模块（stats） |

新增错误码必须使用本模块段位内的数字。

**已占用的 0~99 段（公共）**：

| 码 | 含义 |
|----|------|
| 41 | `EXCEL_TASK_NOT_FOUND` 任务不存在（含「不是自己的任务」，两者返回同一个码） |
| 42 | `EXCEL_TASK_DUPLICATE` 已有同类任务在执行中，请等它结束再提交 |
| 43 | `EXCEL_TASK_BUSY` 任务排队已满，请稍后重新提交 |
| 44 | `EXCEL_TASK_DISABLED` 导入导出功能已关闭 |
| 45 | `EXCEL_TASK_NOT_FINISHED` 任务尚未完成，暂时无法下载 |
| 46 | `EXCEL_TASK_NO_RESULT` 没有可下载的结果文件 |
| 47 | `EXCEL_TASK_PARAMS_INVALID` 导出条件保存失败，请调整筛选条件后重新提交 |

**已占用的 1000 段（user）**：

| 码 | 含义 |
|----|------|
| 1001 | `USERNAME_EXISTS` 用户名已存在 |
| 1002 | `USER_NOT_FOUND` 用户不存在 |
| 1003 | `PASSWORD_ERROR` 用户名或密码错误 |
| 1004 | `USER_DISABLED` 账号已被禁用 |
| 1005 | `USER_LOCKED` 登录失败次数过多，账号已临时锁定，请稍后再试 |
| 1006 | `MINIAPP_AUTH_FAILED` 小程序登录失败，请稍后重试 |

**已占用的 2000 段（product）**：

| 码 | 含义 |
|----|------|
| 2001 | `PRODUCT_CATEGORY_NOT_FOUND` 商品分类不存在 |
| 2002 | `PRODUCT_CATEGORY_HAS_CHILDREN` 分类下存在子分类，不可删除 |
| 2003 | `PRODUCT_CATEGORY_HAS_PRODUCTS` 分类下存在商品，不可删除 |
| 2010 | `PRODUCT_NOT_FOUND` 商品不存在 |
| 2011 | `PRODUCT_SKU_NOT_FOUND` SKU 不存在 |
| 2012 | `PRODUCT_SKU_CODE_DUPLICATE` SKU 编码重复 |
| 2013 | `PRODUCT_SKU_EMPTY` 商品至少需要一个 SKU |
| 2014 | `PRODUCT_OFF_SHELF` 商品已下架 |
| 2020 | `PRODUCT_IMPORT_FILE_INVALID` 导入文件不合法，请下载模板后重新填写 |

**已占用的 3000 段（cart）**：

| 码 | 含义 |
|----|------|
| 3001 | `CART_ITEM_NOT_FOUND` 购物车条目不存在 |
| 3002 | `CART_QUANTITY_INVALID` 购买数量不合法 |
| 3003 | `CART_STOCK_INSUFFICIENT` 库存不足 |
| 3004 | `CART_SKU_DISABLED` 该规格已停售 |

**已占用的 4000 段（order）**：

| 码 | 含义 |
|----|------|
| 4001 | `ORDER_ADDRESS_NOT_FOUND` 收货地址不存在 |
| 4002 | `ORDER_CART_EMPTY` 请先勾选要下单的商品 |
| 4003 | `ORDER_SKU_UNAVAILABLE` 商品已下架或规格已停售 |
| 4004 | `ORDER_STOCK_INSUFFICIENT` 商品库存不足 |
| 4005 | `ORDER_NOT_FOUND` 订单不存在 |
| 4006 | `ORDER_STATUS_ILLEGAL` 当前订单状态不支持该操作 |

**已占用的 5000 段（admin）**：

| 码 | 含义 |
|----|------|
| 5001 | `ADMIN_USER_NOT_FOUND` 管理员不存在 |
| 5002 | `ADMIN_PASSWORD_ERROR` 用户名或密码错误 |
| 5003 | `ADMIN_DISABLED` 账号已被禁用 |
| 5004 | `ADMIN_LOCKED` 登录失败次数过多，账号已临时锁定，请稍后再试 |
| 5005 | `ADMIN_TOKEN_INVALID` 登录已失效，请重新登录 |
| 5006 | `ADMIN_USERNAME_EXISTS` 管理员用户名已存在 |
| 5007 | `ADMIN_OLD_PASSWORD_ERROR` 原密码错误 |
| 5008 | `ADMIN_SELF_OPERATION_FORBIDDEN` 不能对当前登录的管理员账号执行该操作 |
| 5009 | `ADMIN_IMPORT_FILE_INVALID` 导入文件不合法，请下载模板后重新填写 |
| 5010 | `ADMIN_ROLE_NOT_FOUND` 角色不存在 |
| 5011 | `ADMIN_ROLE_CODE_EXISTS` 角色编码已存在 |
| 5012 | `ADMIN_ROLE_IN_USE` 角色已分配给管理员，不可删除 |
| 5020 | `ADMIN_MENU_NOT_FOUND` 菜单不存在 |
| 5021 | `ADMIN_MENU_HAS_CHILDREN` 菜单存在子节点，不可删除 |

**已占用的 6000 段（pay）**：

| 码 | 含义 |
|----|------|
| 6001 | `PAY_ORDER_NOT_FOUND` 订单不存在 |
| 6002 | `PAY_FORBIDDEN` 无权支付该订单 |
| 6003 | `PAY_STATUS_ILLEGAL` 订单当前状态不可支付 |

**已占用的 7000 段（stats）**：

| 码 | 含义 |
|----|------|
| 7001 | `STATS_RECALL_DATE_INVALID` 统计日期不合法，不可晚于今天 |
| 7002 | `STATS_RECALL_PARAM_INVALID` 圈人参数不合法 |
| 7003 | `STATS_RECALL_RUNNING` 圈人任务正在执行中，请稍后重试 |

### 异步导入导出（Excel 任务框架，强约束）

所有「批量导入 / 批量导出」一律走 `spring-shop-common` 的异步任务框架，**不允许**在业务模块里
同步读写 Excel 或自己起线程。

| 组件 | 职责 |
|------|------|
| `ExcelSupport` / `ExcelStreamWriter` / `ExcelExportSupport` | Fesod 封装：流式读、分批写、分页导出模板方法 |
| `ExcelTaskExecutor` | 受理 + 调度 + 状态兜底（`submitImport` / `submitExport`） |
| `ExcelTaskService` | 任务台账读写、失败明细存取、文件清理（**不含调度**，避免循环依赖） |
| `ExcelTaskContext` | 交给业务执行体的上下文：源文件、结果文件、条件还原、进度上报、失败明细 |
| `ExcelFileStorage` | 临时文件存取（`{tmpDir}/{yyyyMMdd}/{taskNo}.{ext}`） |
| `ExcelTaskController`（web 模块） | `GET /api/admin/excel-tasks`、`/{taskNo}`、`/{taskNo}/download` |

**硬约束**：

1. **依赖只能用 Fesod，不要引 POI。** `fesod-sheet` 会传递引入 POI 5.5.x；再单独声明 `poi-ooxml`
   会造成两个 POI 版本共存，运行期抛 `NoSuchMethodError`。
2. **读必须显式指定文件类型**（`ExcelReadOptions.fileType(...)`）。不给类型时 Fesod 探测失败会
   **静默退化成 CSV 解析**：垃圾字节读成 0 行而不报错，用户拿到的是「导入成功但一条都没进去」。
3. **写表头是列优先**：`builder.head(List<List<String>>)` 的外层 list 是**列**，不是行。
   传 `List.of(headers)` 会写出「3 行 × 1 列」的错位表头。统一走 `ExcelStreamWriter`，不要自己拼。
4. **导出必须「边查边写」**：按 `excel.task.export-page-size` 分页拉取，每页写完立刻丢弃。
   禁止先 `selectList` 全量再写盘 —— 十万行的结果集会把堆撑爆。分页取数用
   `new Page<>(current, pageSize, false)`（`searchCount=false`，导出不需要总数）。
5. **导出结果落盘、下载时流式拷贝**：Controller 写 `HttpServletResponse`，**不要**返回
   `ResponseEntity<byte[]>`（后者会把整个文件读进堆）。下载接口返回 `void` 是文件下载的固有例外。
6. **任务状态必须落库**（`excel_task` 表），不用 Redis / 内存：导入要跑几十秒到几分钟，
   用户会刷新页面、应用会滚动重启，放内存就是「一直转圈」。
7. **提交人身份在受理阶段取好**：`UserContext` 是 `ThreadLocal`，**不会**传播到异步线程，
   必须在 Controller 里 `UserContext.getUserId()` 取出来当参数传下去。
8. **执行体不能用 `@Transactional`**：它跑在异步线程里，自调用不走代理；批级事务用
   `TransactionTemplate`，语义是「批内原子、批间独立」，并配合「整批回滚后逐组/逐行重试」，
   让单条唯一键冲突降级为单行失败而不是整批失败。
9. **执行体必须无条件收尾上报**。只在「有分组/有批次」时上报进度，会让「全部行都在校验阶段失败」
   的文件以 `处理 0 行 / 失败 0 行` 收场，而失败明细里明明有内容。
10. **同一业务类型 + 同一提交人不允许并发任务**（`assertNotRunning`），否则连点就能把线程池打满；
    队列满时 `AbortPolicy` 直接拒绝并提示「排队已满」，**不要**用 `CallerRunsPolicy`
    （HTTP 线程会亲自去跑几万行导入，请求必然超时）。
11. **任务归属校验而不是权限码**：能提交任务说明已经过了权限码；查询/下载一律用 `created_by`
    做归属校验，「任务不存在」与「不是我的任务」返回同一个错误码，不给探测他人任务号的机会。
12. **阈值全部配置化**（`excel.task.*`）：批大小、线程数、分页大小、行数上限这些参数和部署机器
    强相关，硬编码就只能改代码重发。

**新增一个导出只需三步**：写一个 `@ExcelProperty` 标注的行模型（含 `from(VO)` 静态转换）、
在查询服务里加一个 `xxxExportPage(query, current, pageSize)`（复用列表页的筛选口径、
`searchCount=false`）、写一个 `XxxExportService` 调 `ExcelExportSupport.export(...)`。
分页循环、进度上报、空数据写表头都由模板方法兜住。

> 📎 **配套技能**：`.workbuddy-ai/skills/spring-shop-excel-task/` 是本框架的**可执行操作手册** ——
> 代码地图（每个类在哪、干什么）、接口清单与权限码、「新增一个导入/导出」的步骤、
> 装配陷阱（bean 名撞车 / `@MapperScan` 与 `*.mapper` 包 / 包名目录不一致）、
> Fesod 三个静默失败的坑、异步集成测试铁律、以及一张「现象 → 先看哪里」排查表。
> 本节是**规范来源**，那份技能是**动手时的向导**；两者冲突时以本节为准并顺手修技能。
>
> 其中 `references/task-internals.md` 记了任务表字段的设计理由与 `excel.task.*` 每一项
> 改了会有什么后果，`references/fesod-pitfalls.md` 记了 Fesod 的正确写法与 `javap` 探针命令。

### 参数校验

- 入参 DTO 使用 `spring-boot-starter-validation` 的注解（`@NotBlank`、`@NotNull`、`@Size` 等），并在 Controller 参数上加 `@Valid` / `@Validated`。

### 接口文档规范（springdoc / OpenAPI，强约束）

接口文档由 springdoc 从代码注解自动生成（`/v3/api-docs`、Swagger UI `/swagger-ui.html`）。
文档是给人的，**中文说明必须写在代码注解里**，不让使用方去猜字段含义：

- **入参 DTO 与出参 VO**：类上与**每个字段**都要标 `@Schema(description = "中文说明")`。
  状态/类型/枚举字段在说明里写清取值（如 `上架状态：1-上架，0-下架`），金额字段写明单位（如 `单位：元`）。
  Excel 导出行模型（`*ExportRow`）不进文档，无需标注。
- **Controller**：类上标 `@Tag`，方法上标 `@Operation(summary = ...)`；**成功响应**用
  `@ApiResponse(responseCode = "200", description = "返回…的中文说明")` 说明返回什么数据
  （只给描述即可，springdoc 会按方法返回类型自动补 schema）。返回 `Result<Void>` 的写「操作成功，无返回数据」。
- **GET 的 POJO 查询参数**：必须加 `@ParameterObject`，否则 springdoc 把整个对象折叠成一个
  `$ref` 参数，使用方照文档拼参会失败。
- **路径变量 / 普通请求参数**：用 `@Parameter(description = "中文说明")`。
- 401 / 403 / 500 等通用失败响应由 [OpenApiConfig](spring-shop-web/src/main/java/com/springshop/web/config/OpenApiConfig.java)
  的 `OpenApiCustomizer` 统一补中文，**无需**在 Controller 上逐个标注。

> 自检：打开 Swagger UI 浏览，任何接口的入参、出参字段、响应说明都不应出现空白或英文默认值（`OK` / `Forbidden`）；
> 也可 `curl -s http://localhost:6001/v3/api-docs` 检索 `description`。

### 日志与 profile 约定（强约束）

运行时的 profile **只支持 `dev`（默认）和 `prod`**，由 `SPRING_PROFILES_ACTIVE` 控制。

改动 `logback-spring.xml` 或新增 profile 时必须注意：

- **数据源只在 `application-dev.yml` / `application-prod.yml` 里定义**，`application.yml` 本身不含 `spring.datasource`。
  用其他 profile 启动会直接报 `Failed to determine a suitable driver class`。
- **`<root>` 必须覆盖到所有 profile**。当前写法是 `dev` / `prod` 各一段，外加 `!dev & !prod` 兜底段。
  若只留 `dev`/`prod`，其他 profile 下根 logger 会一个 appender 都没有 ——
  Logback **不会**退回默认控制台输出，应用会「照常启动但一行日志都不打印」，
  启动失败只剩退出码 1，排查时无从下手。
- **不要在 `<configuration>` 里直接再写一个 `<root>`** 来做兜底：Logback 对多个 `<root>` 是**累加 appender**
  而不是覆盖，会导致 `dev`/`prod` 下同一行日志打印两遍。用 `springProfile name="!dev & !prod"` 才是正确写法。
- `test` profile 的数据源在 `spring-shop-web/src/test/resources/application-test.yml`（测试作用域，**不会打进 jar**），
  它只服务于 `mvn test`，**不能**用 `java -jar --spring.profiles.active=test` 启动应用。

### MyBatis-Plus 约定

- Mapper 接口继承 `BaseMapper<T>`。
- 简单 CRUD 用 MP 内置方法，复杂 SQL 写在 `src/main/resources/mapper/**/*.xml`（对应 `mapper-locations: classpath*:/mapper/**/*.xml`）。
- 主键自增（`id-type: auto`），实体主键字段标 `@TableId(type = IdType.AUTO)`。

## 数据库配置约定

- 默认连接 `localhost:3306/spring_shop`，用户名 `root`。
- 密码通过环境变量 `MYSQL_PASSWORD` 覆盖，**禁止把真实密码硬编码提交**。

### Flyway 迁移脚本约定

- 表结构变更一律通过 `spring-shop-web/src/main/resources/db/migration/` 下的 `V{n}__描述.sql` 管理，应用启动时自动执行。
- **禁止修改已提交的迁移脚本**：Flyway 会校验脚本 checksum，改动会导致已应用过该脚本的库启动失败。需要变更表结构时新增 `V{n+1}` 脚本。
- 新脚本**不带 `IF NOT EXISTS`**（仅 V1~V3 因兼容历史手建库保留）。
- 多人并行时提前 pull 远端，避免重复的 V 版本号；冲突时先合入小的 PR。
- 集成测试使用 `spring-shop-web/src/test/resources/db/migration-test/` 下的测试专用脚本（索引名全局唯一化以兼容 H2 的限制），与主脚本保持同步。
- **⚠️ CI 红线 — 索引名全局唯一**：H2 中索引名是库级唯一，而 MySQL 仅要求表级唯一。主迁移脚本里不同表的索引如果重名（如两张表都叫 `idx_product_id`），在测试迁移脚本中必须重命名为全局唯一的名字（如 `idx_sku_product_id`、`idx_image_product_id`）。每新增一张带非主键索引的表，都要同步检查 `migration-test/` 副本并确保无重名索引。

### 测试编写与 CI 通过规范（强约束）

新增业务模块或新接口时，必须按类型选择对应的测试模板并满足以下硬约束，否则 CI 必挂：

#### 1. 接口集成测试（`spring-shop-web/src/test/java/**`，`*IntegrationTest`）

使用 `@SpringBootTest + @AutoConfigureMockMvc` 启动完整 Spring 容器，覆盖 Controller→Service→Mapper 全链路。**类头必须同时声明以下 4 项配置，缺一不可：**

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")                                  // (1) 强制启用 test profile：H2 内存库 + 无 Redis
@Transactional                                           // (2) 每个用例后自动回滚数据库，用例间数据隔离
class XxxIntegrationTest {

    @MockBean                                            // (3) Mock 掉真实 Redis 连接，CI 环境无 Redis 实例
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // @Test ...
}
```

**例外：涉及异步任务（Excel 导入/导出）的集成测试不得加 `@Transactional`。**

任务执行体跑在独立的 `excel-task` 线程池里，用的是**另一条数据库连接**。测试方法的事务对它不可见：

- 测试里建的前置数据（分类、商品）在异步线程里「查不到」→ 导入整批报「分类不存在」；
- 任务表的状态回写落在另一个未提交的快照上 → 轮询永远读到「未开始」。

所以这类测试必须：

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 刻意不加 @Transactional：执行体在另一个线程、另一条连接上
class XxxImportIntegrationTest {

    /** 本次 JVM 运行的唯一后缀，避免共享 H2 里的历史数据撞唯一键 */
    private static final String RUN_TAG = Long.toString(System.nanoTime() % 1_000_000);
}
```

1. 所有测试数据带 `RUN_TAG` 唯一后缀（H2 在同一个 surefire JVM 内是共享的）；
2. 断言**按名称/编码过滤，不依赖任何「总数」**；
3. 用**轮询**等任务到终态（50ms 一次 + 上限 30s），不要 `sleep` 固定时长；
4. **`POST` 之外的动作也要看业务码**：业务失败同样是 HTTP 200，只看 `status().isOk()` 会把「没受理」当成「已受理」；
5. **不要直接对 `Result.data` 调 `asLong()/asText()`**：`data` 为 JSON `null` 时 Jackson 给的是
   `NullNode`（非 null 引用），`asLong()` 静默返回 `0` —— 于是「按 id 导出」变成「导出 id=0 的数据」，
   一条都查不到且不报错。先断言 `code == 200` 并检查 `data` 非 null。

> 反面教材（本次踩坑实录）：`POST /api/admin/categories` 返回 `Result<Void>`，
> 测试用 `data.asLong()` 取分类 id 拿到 `0`，连带商品创建失败也拿到 `0`，
> 最终「导出选中商品」导出 0 行 —— 全程没有任何异常，只有一个莫名其妙的断言失败。
>
> 另一个坑：**分类表对名称没有唯一约束**，每个用例都建一次同名分类会堆出多条，
> 而导入按名称匹配分类时「同名多条宁可整组失败也不猜」→ 后跑的用例莫名其妙全军覆没。
> 需要跨用例共享的分类，请幂等地只建一次。

#### 2. 需要真实 Servlet 容器时（`multipart` / 大小限制 / 真实 HTTP 语义）

MockMvc 是**伪造**请求，它构造 `MockMultipartHttpServletRequest`，**直接跳过容器的 multipart 解析**。
所以凡是依赖 Servlet 容器真实行为的功能，必须用 `RANDOM_PORT` 另写一个测试类：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class XxxMultipartIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;   // 真实 HTTP

    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    // 注意：这里刻意不加 @Transactional
}
```

两条硬约束：

- **不要加 `@Transactional`**：请求跑在 Tomcat 工作线程里，测试方法的事务回滚不了服务端事务，
  加了只会制造「已经回滚」的错觉。因此这类测试**只能做只读或必然失败的操作**，不得写库 ——
  `jdbc:h2:mem:spring_shop_test` 在同一个 surefire JVM 内是**共享**的，写脏会串到其他测试类。
- **multipart 的文件字段必须带文件名**：用 `ByteArrayResource` 时要覆写 `getFilename()`，
  否则该 part 会退化成普通字段，服务端不会按「上传文件」处理。

> 血泪教训：`spring.servlet.multipart.max-file-size` 单独配置**并不能保证客户端体验** ——
> 还要配 `server.tomcat.max-swallow-size`（当前 12MB，略大于 `max-request-size`）。
> 否则超过 Tomcat 默认 `maxSwallowSize`（2MB）的请求会被容器直接断连，
> 客户端只看到 `Broken pipe`，`GlobalExceptionHandler` 的可读提示送不出去。
> 参考 `ProductImportMultipartIntegrationTest`。

> **违反后果（本次踩坑实录）**：
> - 缺 `@ActiveProfiles("test")` → 默认加载 dev profile → 尝试连接 `localhost:3306` 真实 MySQL / `localhost:6379` 真实 Redis → CI 环境无服务 → 连接超时失败。
> - 缺 `@Transactional` → 前一个用例插入的脏数据影响后续用例 → 断言失败或唯一键冲突。
> - 缺 `@MockBean StringRedisTemplate` → Spring 启动时创建 Lettuce Redis 客户端 → 端口连不上 → Bean 创建失败。

#### 3. Service 层单元测试（业务模块 `src/test/java/**`，`*ServiceImplTest`）

使用 `@ExtendWith(MockitoExtension.class)` 纯 Mockito 隔离所有 Mapper/外部依赖，**不需要**启动 Spring 容器。模板：

```java
@ExtendWith(MockitoExtension.class)
class XxxServiceImplTest {
    @Mock private XxxMapper xxxMapper;
    @InjectMocks private XxxServiceImpl xxxService;
    // @Test ...
}
```

#### 4. 新增业务模块的安全白名单检查

如果新增前台公开接口（匿名可访问，如商品浏览），必须在 `SecurityConfig#appSecurityFilterChain` 的 `permitAll()` 列表中加入对应路径；如果新增后台管理员接口（`/api/admin/**`），需要同时做两件事：
1. 在 `AdminDataInitializer#buildMenus` 中注册对应菜单与按钮级权限（`product:product:create` 形式的 permissionCode）；
2. Controller 方法上标注 `@PreAuthorize("hasAuthority('...')")` 与权限代码保持一致。

> 漏做任一项：要么 401/403 拒绝访问，要么方法级鉴权抛权限异常。

好消息是这一条**已经由测试兜住了**：`AdminPermissionCoverageIntegrationTest` 会从运行时容器里
把真实生效的 `@PreAuthorize` 表达式全部扫出来，再和 `menu` 表对账，断言：

1. 每个权限码都已在 `menu` 表注册且启用；
2. 每个权限码都已授予 `ADMIN` 角色（只注册不授权同样是 403）；
3. 兜底断言扫描确实扫到了注解，避免反射失效导致用例「空转全绿」。

所以漏注册权限不会再静默上线，而是 CI 直接红灯。注意该测试的解析规则目前只认
`hasAuthority('...')`；若引入 `hasAnyAuthority` / `hasRole` 等写法，用例会**主动失败**提醒你同步解析逻辑，
不会静默跳过。

#### 5. 改动初始数据 / 种子数据时（`AdminDataInitializer`）

初始数据不是「只在空库上跑一次」——它在**每一个已上线的老库**上每次启动都会跑。因此：

- 新增菜单 / 按钮权限时，必须以 `menu.permission_code` 为幂等键**逐项补齐**，
  不能写成「表非空就整体跳过」，否则新权限永远写不进老库，对应的 `@PreAuthorize` 永久 403。
- 授权关联（`ensureRoleMenus` / `admin_user_role`）**只增不减**，重复启动不得产生重复行。
- ⚠️ `menu.permission_code` 上**没有唯一索引**，重复插入不会被数据库拦住，只能靠幂等逻辑保证；
  而 `role_menu` / `admin_user_role` 上有唯一键，重复授权会直接抛异常。
- 改动初始数据后必须保证 `AdminDataInitializerUpgradeIntegrationTest` 通过。
  该测试用**原生 SQL 物理删除**（不是逻辑删除）造出「老库」再重新触发一次初始化，
  断言补齐 + 幂等；新增初始数据项时应同步扩充这个测试。

#### 6. PR 前本地必跑清单（CI 的等价执行）

```bash
# 等同 CI 中执行的 mvn -B clean verify
mvn clean verify
```

确认输出 `BUILD SUCCESS` 后再提交 PR，不要依赖 CI 的报错来回调。

## 构建与运行

```bash
# 全量构建
mvn clean package

# 仅编译检查
mvn compile

# 运行全部测试（单元 + 集成，使用 H2 内存库，无需本地 MySQL）
mvn test

# 启动（推荐：先 package，再跑可执行 jar）
java -jar spring-shop-web/target/spring-shop-web-1.0.0.jar

# 启动后健康检查
curl http://localhost:6001/api/health
```

> ⚠️ **不要用 `mvn -pl spring-shop-web -am spring-boot:run`**：`-am` 会把父聚合 POM
> （`spring-shop`，packaging = pom）拉进反应堆，`spring-boot:run` 是普通 goal、会先在它上面执行，
> 报 `Unable to find a suitable main class`。要用 `spring-boot:run` 就去掉 `-am`，
> 但前提是兄弟模块已 `mvn install` 到本地仓库。
> 复现反应堆范围：`mvn -pl spring-shop-web -am validate`（会构建 10 个模块，第 1 个是 `spring-shop`）。

## 工作流规范

### 开发任务流程

1. **先读后改**：修改任何文件前，先阅读目标文件及相关上下文。
2. **小步提交**：一个功能一个改动，保持 diff 聚焦。
3. **保持风格一致**：新代码遵循本文件与现有代码风格。
4. **注释到位**：复杂业务逻辑在关键步骤保留注释解释「为什么这样做」，降低长期维护成本。
5. **测试跟随**：新功能/修复必须附带测试（Service 层单元测试 + 必要的接口集成测试），提交前保证 `mvn test` 全绿。

### 计划文档规范（强约束）

实施计划文档（如 `docs/superpowers/plans/*.md`）**必须精简、可快速人工复核**，禁止写几千行：

- 全文原则上不超过 **200 行**。
- 只写每个步骤的**核心实现点**：目标一句话 + 改动文件与关键行为 + 验收方式（测试类/命令/提交号）。
- **不粘贴大段代码**（单个代码片段不超过 10 行，且仅限非显而易见的关键逻辑）；测试代码、常规 CRUD、配置细节不进文档。
- 不重复本规范已有内容（分层、测试模板、错误码等直接引用对应章节）。
- 复核标准：reviewer 读完计划应能在几分钟内回答「改哪些文件、为什么、怎么验收」；做不到即为计划写得太细。

### 分支与合并（多人协作）

- `main` 为受保护主干，**禁止直接 push**；开发在 `feature/<模块>-<功能>` 分支进行，完成后通过 Pull Request 合入 main。
- PR 必须通过 CI（GitHub Actions 自动执行 `mvn -B verify`）并经至少 1 人评审后方可合并。
- 每个 PR 保持单一功能；合入前先同步远端 `main` 减少冲突。
- 多人并行开发时，各业务模块（商品/订单/购物车）分属不同分支互不阻塞。

### 代码提交

- 提交信息使用中文，遵循格式：`<类型>[(<范围>)]: <简述>`（`<范围>` 可选，如模块名）
  - 类型：`feat`（新功能）、`fix`（修复）、`refactor`（重构）、`docs`（文档）、`chore`（杂项）
  - 示例：`feat: 新增用户注册接口`、`feat(product): 新增商品导入`
  - 简述不超过 100 字符
- **提交信息已启用自动校验**：仓库根目录 `.githooks/commit-msg` 会拦截不合规的提交。首次 clone 后需执行一次 `git config core.hooksPath .githooks` 才能生效。
- 不提交 `target/`、IDE 配置等（已在 `.gitignore` 中排除）。

### 禁止事项

- 禁止在 common 模块写业务逻辑。
- 禁止直接修改 `target/` 目录下的产物。
- 禁止提交真实数据库密码、密钥等敏感信息。
- 禁止在 Controller 中写业务逻辑或直接访问 Mapper。
- 禁止不经评审修改 `spring-shop-common` 的公共能力（全员依赖，改动影响面大）。
