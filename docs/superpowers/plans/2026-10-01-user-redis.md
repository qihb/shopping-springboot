# 用户端 Redis 能力落地实施计划（已执行完毕）

## Goal

为用户端补齐 Redis 生产化能力（复刻 admin 成熟模式）+ 商品读缓存：

- **A. 用户安全**：登录失败锁定（5 次锁 15 分钟，错误码 `USER_LOCKED(1005)`）；`POST /api/auth/logout` + token 黑名单（TTL = jwt.expiration）；前台过滤器只接受 `userType=USER` 且校验黑名单（修复 ADMIN token 越权）。
- **B. 读缓存**：商品详情 `product:detail:<id>` TTL 30min+抖动、空值缓存 60s；分类树 `category:tree` TTL 1h；写操作主动 DEL。

## 关键决策

- 全部用 `StringRedisTemplate` + `RedisKeys` 集中管理 key，value 存 JSON（Boot 自动配置的 `ObjectMapper`），不引入 Spring Cache。
- common 已依赖 redis starter，无需改 pom；`USER_LOCKED(1005)` 占用户模块 1000 段位。
- 缓存读写/序列化失败一律 try/catch 静默降级走库，不阻断主流程。
- 已知权衡：写操作 DEL 在事务提交前执行，存在极端回填旧值竞态，单体 + TTL 兜底下可接受（与 admin 一致）。
- CI 无 Redis：所有触发登录的集成测试必须 `@MockBean StringRedisTemplate`（Auth 集成测试原本缺失，Task 4 补齐）。

## Tasks（TDD 红绿节奏，均已交付）

| # | 核心实现点 | 验收 | 提交 |
|---|-----------|------|------|
| 1 | `RedisKeys` 新增 `userLoginFailCount` / `userTokenBlacklist` / `productDetail` / `categoryTree`；`ResultCode` 新增 `USER_LOCKED(1005)` | 编译通过 | a84d768 |
| 2 | `UserServiceImpl` 登录锁定：登录前查计数 ≥5 抛 `USER_LOCKED`；密码错误 `increment`（首错设 TTL 15min）；成功清零。注：`@Value` 字段 Mockito 无法注入，放弃 `@InjectMocks` 改手动构造传 `7200000L` | `UserServiceImplTest` 锁定用例全绿 | 918968b |
| 3 | `AuthController` 新增 `POST /api/auth/logout`；`UserService.logout` 将 token 写入黑名单，TTL = jwt.expiration | 单测 + 接口用例绿 | deacb54 |
| 4 | 两个 JWT 过滤器：token 存在时校验 `userType`（前台 USER / 后台 ADMIN）与黑名单，不匹配/命中则不设置认证；`AuthIntegrationTest` 补 `@MockBean StringRedisTemplate`（防 CI 挂）+ 新增 ADMIN token 访问前台 401 用例 | 新用例绿、原 10 用例不回归 | 5ef3900 |
| 5 | `AuthIntegrationTest` 补锁定（stub `get` 返回 `"5"` 绕过 mock 不累加的限制）与登出后原 token 401 用例 | 新用例绿 | 229e814 |
| 6 | `ProductQueryServiceImpl.detail` 读缓存壳：命中返 JSON 反序列化；未命中查库回填 TTL 30min + 随机抖动（30~35min）防雪崩；空结果缓存 60s 防穿透 | `ProductQueryServiceImplTest` 缓存用例绿 | a81858a |
| 7 | `ProductManageServiceImpl`：`update` / `updateStatus` 成功后 DEL `product:detail:<id>`；`create` 不 DEL（无旧缓存）。注：update 走 `saveImages` 需补 `ProductImageMapper` mock（计划疏漏，执行时修正） | 3 个失效用例绿 | 6fe3f9c |
| 8 | `CategoryServiceImpl.tree()` 缓存壳（TTL 1h；损坏 JSON 视为未命中回源）；`create` / `update` / `delete` 末尾 DEL 树缓存 | `CategoryServiceImplTest` 9 用例绿 | f29d43a |
| 9 | 全量回归 `mvn clean verify`；修复 Task 4 引入的回归（见下） | BUILD SUCCESS，129 用例全绿 | 794dccd |

## 执行中发现并修复的回归

- **现象**：Task 4 后 Admin 3 + Order 1 集成用例 500（`AuthenticationCredentialsNotFoundException`）。
- **根因**：两个 JWT 过滤器是 `@Component`，被 Boot 注册为全局过滤器且在 security 链之后执行；对 ADMIN 请求执行 `clearContext()` 会清掉 admin 链刚写入的认证。
- **修复**：userType 不匹配 / 黑名单命中分支只 `return` 不 `clearContext()`（794dccd）。

## Verification

- 每步：模块级 `mvn test`；最终 `mvn clean verify` 全绿（user 13 / product 26 / cart 27 / order 23 / web 集成 40）。
- 可选手动冒烟（需本地 MySQL + Redis）：注册登录 → 详情首查后 Redis 出现 `product:detail:<id>` → 登出后原 token 访问 `/api/user/me` 返 401 → 连错 5 次密码登录返 code=1005。
- 复核方式：按上表提交号 `git show <hash>` 对照「核心实现点」逐条检查。
