# AGENTS.md — spring-shop 项目协作规范

本文件面向在本仓库中工作的开发者与 AI 编码助手，用于统一协作方式与代码规范，保证代码风格一致、可维护。

## 项目概述

- **项目名称**：spring-shop
- **项目定位**：生产级 Java 电商网站后端，单体应用架构（可扩展）
- **基础框架**：Spring Boot 3.5.16 + Maven 多模块 + Java 21
- **持久层**：MyBatis-Plus 3.5.17 + MySQL 8
- **当前状态**：用户、商品、购物车、订单、支付、管理后台六大业务模块已全部交付（注册/登录/JWT 认证、RBAC 权限中心、操作审计、下单库存扣减、模拟支付），测试基座与 CI 已就绪

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 语言 |
| Spring Boot | 3.5.16 | 框架 |
| Maven | - | 构建工具 |
| MyBatis-Plus | 3.5.17 | ORM / 持久层 |
| MySQL | 8.x | 数据库 |
| Redis | 6.x | 缓存（登录失败锁定、token 黑名单） |
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
│       └── security/           # JWT 工具 JwtTokenProvider、用户上下文 UserContext、RedisKeys
├── spring-shop-user/           # 用户模块：注册、登录（含小程序）、当前用户、多端标识
├── spring-shop-product/        # 商品模块：分类、SPU/SKU/图片，前后台列表与详情（读服务聚合）、库存条件扣减
├── spring-shop-cart/           # 购物车模块：加购、改数量、勾选、删除与购物车列表
├── spring-shop-order/          # 订单模块：收货地址、下单（快照 + 扣库存）、状态流转、后台发货
├── spring-shop-pay/            # 支付模块：模拟支付、支付记录（幂等 + 条件更新防并发）
├── spring-shop-admin/          # 管理后台模块：管理员认证、RBAC 权限中心、操作审计
│   └── src/main/java/com/springshop/admin/
│       ├── aspect/             # 操作审计注解 + AOP 切面
│       ├── config/             # 初始数据初始化器 AdminDataInitializer
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

**已占用的 7000 段（stats）**：

| 码 | 含义 |
|----|------|
| 7001 | `STATS_RECALL_DATE_INVALID` 统计日期不合法，不可晚于今天 |
| 7002 | `STATS_RECALL_PARAM_INVALID` 圈人参数不合法 |
| 7003 | `STATS_RECALL_RUNNING` 圈人任务正在执行中，请稍后重试 |

### 参数校验

- 入参 DTO 使用 `spring-boot-starter-validation` 的注解（`@NotBlank`、`@NotNull`、`@Size` 等），并在 Controller 参数上加 `@Valid` / `@Validated`。

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

> **违反后果（本次踩坑实录）**：
> - 缺 `@ActiveProfiles("test")` → 默认加载 dev profile → 尝试连接 `localhost:3306` 真实 MySQL / `localhost:6379` 真实 Redis → CI 环境无服务 → 连接超时失败。
> - 缺 `@Transactional` → 前一个用例插入的脏数据影响后续用例 → 断言失败或唯一键冲突。
> - 缺 `@MockBean StringRedisTemplate` → Spring 启动时创建 Lettuce Redis 客户端 → 端口连不上 → Bean 创建失败。

#### 2. Service 层单元测试（业务模块 `src/test/java/**`，`*ServiceImplTest`）

使用 `@ExtendWith(MockitoExtension.class)` 纯 Mockito 隔离所有 Mapper/外部依赖，**不需要**启动 Spring 容器。模板：

```java
@ExtendWith(MockitoExtension.class)
class XxxServiceImplTest {
    @Mock private XxxMapper xxxMapper;
    @InjectMocks private XxxServiceImpl xxxService;
    // @Test ...
}
```

#### 3. 新增业务模块的安全白名单检查

如果新增前台公开接口（匿名可访问，如商品浏览），必须在 `SecurityConfig#appSecurityFilterChain` 的 `permitAll()` 列表中加入对应路径；如果新增后台管理员接口（`/api/admin/**`），需要同时做两件事：
1. 在 `AdminDataInitializer#buildMenus` 中注册对应菜单与按钮级权限（`product:product:create` 形式的 permissionCode）；
2. Controller 方法上标注 `@PreAuthorize("hasAuthority('...')")` 与权限代码保持一致。

> 漏做任一项：要么 401/403 拒绝访问，要么方法级鉴权抛权限异常。

#### 4. PR 前本地必跑清单（CI 的等价执行）

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

# 运行 web 模块
mvn -pl spring-shop-web -am spring-boot:run

# 启动后健康检查
curl http://localhost:6001/api/health
```

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

- 提交信息使用中文，遵循格式：`<类型>: <简述>`
  - 类型：`feat`（新功能）、`fix`（修复）、`refactor`（重构）、`docs`（文档）、`chore`（杂项）
  - 示例：`feat: 新增用户注册接口`
  - 简述不超过 100 字符
- **提交信息已启用自动校验**：仓库根目录 `.githooks/commit-msg` 会拦截不合规的提交。首次 clone 后需执行一次 `git config core.hooksPath .githooks` 才能生效。
- 不提交 `target/`、IDE 配置等（已在 `.gitignore` 中排除）。

### 禁止事项

- 禁止在 common 模块写业务逻辑。
- 禁止直接修改 `target/` 目录下的产物。
- 禁止提交真实数据库密码、密钥等敏感信息。
- 禁止在 Controller 中写业务逻辑或直接访问 Mapper。
- 禁止不经评审修改 `spring-shop-common` 的公共能力（全员依赖，改动影响面大）。
