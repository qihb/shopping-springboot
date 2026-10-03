# 管理后台模块技术梳理

> 面向接手者：本文不只记录“最后做成了什么”，也说明“为什么这样做”。配套协作规范见 [AGENTS.md](../AGENTS.md)。

## 一、先回答架构问题：后台和用户端放一起，还是拆成两个项目？

这里要先区分两个层面：

1. **后端代码仓库怎么组织**
2. **前端站点怎么部署**

当前项目讨论的是后端架构，所以这里重点比较的是：

- **方案 A：一个后端项目，按模块拆分**  
  例子：`spring-shop-user` 负责前台用户能力，`spring-shop-admin` 负责后台管理能力，统一由 `spring-shop-web` 启动。
- **方案 B：两个独立后端项目**  
  例子：`shop-user-service` 提供前台接口，`shop-admin-service` 提供后台接口，各自独立启动、独立部署。

### 方案 A：放在一个项目里（当前采用）

#### 好处

- **共享成本低**：JWT、统一响应、异常体系、MyBatis-Plus、Redis、Flyway 都能复用，不需要在两个仓库各维护一套。
- **业务边界还不复杂时推进最快**：商品、订单、用户、后台权限都在一个仓库，联调和重构成本更低。
- **数据模型天然共享**：后台管商品、订单，本质上就是在操作同一套业务数据，不需要跨服务调用。
- **对当前阶段更合适**：现在还是单体后端，业务量和团队规模都没到“必须拆服务”的阶段。

#### 短处

- **发布耦合**：后台改动和前台接口改动最终还是一次整体发布。
- **代码边界需要自觉维护**：如果没有模块约束，后台逻辑和前台逻辑容易混着写。
- **资源隔离弱**：后台和前台共用一个进程，极端情况下后台慢查询也可能影响前台接口。

#### 适用场景

- 团队人数不多
- 业务仍在快速演进
- 更重视交付速度和一致性
- 还没到按服务做独立扩缩容的阶段

### 方案 B：拆成两个独立项目

#### 好处

- **发布独立**：后台改权限、菜单、运营配置时，不必跟前台接口一起发版。
- **资源隔离更彻底**：前台高并发和后台低频重操作互不影响。
- **权限与安全边界更清晰**：后台服务只暴露管理接口，更容易在网络层、网关层做隔离。
- **适合更大的团队**：前台业务组和后台运营组可以独立演进。

#### 短处

- **重复建设更多**：认证、日志、异常、基础配置要维护两份，或者抽更重的基础设施。
- **跨项目协作成本更高**：接口变更、字段同步、联调都更重。
- **数据一致性与调用链更复杂**：后台想查用户、商品、订单时，要么直连同库，要么多服务调用。
- **对当前项目来说容易过早复杂化**：还没把核心业务做完，就先引入两套服务治理，收益不一定大于成本。

#### 适用场景

- 团队分工明确，后台与前台由不同小组长期维护
- 后台接口和前台接口发布频率差异很大
- 需要独立扩容、独立容灾、独立网络隔离
- 已经有网关、注册中心、统一配置、链路追踪等基础设施

### 当前项目建议

**当前阶段更建议保留“一个仓库 + 多模块 + 前后台接口隔离”的做法，不建议现在就拆成两个后端项目。**

原因很直接：

- 你现在的核心矛盾不是“服务拆得不够细”，而是“商品、购物车、订单这些主业务还没落地”；
- 后台和前台本质上共用同一套商品、订单、用户数据，先在单体里把边界划清，比先拆服务更实用；
- 现在已经通过 `spring-shop-user` / `spring-shop-admin` / `SecurityConfig` 双过滤链把边界拉出来了，后续真要拆，也有迁移基础。

**更稳妥的生产化路径是：**

1. 后端先保持单仓多模块
2. 前端页面可以拆成两个独立前端项目（商城 Web / 管理后台 Web）
3. 等商品、订单、库存、支付都成熟后，再决定后台是否单独拆服务

## 二、当前管理后台的模块边界

```
spring-shop-admin
├── controller   # 后台接口：登录、管理员账号、角色、菜单、操作日志、个人中心
├── service      # 管理后台业务逻辑（含管理员 Excel 导入）
├── mapper       # 管理员 / 角色 / 菜单 / 审计日志访问
├── entity       # admin_user / role / menu / role_menu / operation_log 等实体
├── dto          # 入参：登录、管理员增改、重置密码、分配角色、角色保存、菜单保存、日志查询
├── vo           # 出参：管理员信息、角色、菜单树、导入结果、操作日志
├── security     # AdminUserPrincipal / AdminUserDetailsService / AdminJwtAuthenticationFilter
├── aspect       # @OperationLog 注解与切面
└── config       # AdminDataInitializer 初始数据注入
```

### 为什么单独建 `spring-shop-admin` 模块，而不是继续塞进 `spring-shop-user`

- 管理员和前台用户是两类主体，认证方式相似，但**身份、权限、接口边界完全不同**
- 后台要支持 RBAC、菜单权限、操作审计，这些都不应污染前台用户模块
- 独立模块后，后续新增“用户管理 / 商品管理 / 订单管理 / 配置中心”时边界更清晰

## 三、核心设计与实现细节

## 1. 认证模型：为什么不是复用前台登录？

当前 JWT 中新增了 `userType` claim：

- 前台用户：`USER`
- 后台管理员：`ADMIN`

这样做的考虑点：

- **只靠用户名无法区分前后台主体**
- **只靠 URL 白名单不够稳**，因为两个过滤链共存时，前台 token 也可能先被认证成功
- token 内带 `userType` 后，后台过滤器可以明确拒绝非 `ADMIN` token

对应实现：

- `JwtTokenProvider.generateToken(userId, username, userType)`
- `JwtTokenProvider.getUserType(token)`
- `SecurityConfig` 中两条过滤链按 `/api/admin/**` 与其他路径分开处理

## 2. 双过滤链：为什么不是一个链里写很多 if/else？

当前做法：

- `@Order(1)`：后台链，只处理 `/api/admin/**`
- `@Order(2)`：前台链，处理剩余请求

这样做的考虑点：

- **职责更清晰**：后台和前台的白名单、认证主体、授权规则分开维护
- **避免越权**：后台链除了要求“已认证”，还额外校验 `principal instanceof AdminUserPrincipal`
- **便于后续扩展**：以后加 `/api/admin/users`、`/api/admin/products`，都能直接复用后台链规则

如果只用一条链，后面很容易演变成：

- 路径判断越来越多
- 前后台规则混在一起
- 某次改白名单时误伤另一侧接口

## 3. 登录失败锁定：为什么把计数放 Redis，不放数据库？

当前策略：

- 连续失败 5 次
- 锁定 15 分钟
- key：`admin:login:fail:{username}`

考虑点：

- **临时态数据不适合落数据库**：失败计数是短时状态，写库会增加表污染和清理成本
- **Redis 更适合带 TTL 的计数器**
- **应用重启不丢状态**：比 JVM 内存计数更稳

当前实现细节：

1. 登录前先读 Redis，检查是否锁定
2. 密码错误时 `increment`
3. 第一次失败时给 key 设置 15 分钟过期
4. 登录成功后删除失败计数

## 4. token 黑名单：为什么 JWT 还要配 Redis？

JWT 是无状态的，天然问题是：

- 服务端不保存 session
- token 一旦签发，在过期前默认都有效

所以后台退出登录时，如果什么都不做，token 其实还能继续使用。

当前做法：

- 登出时把 token 放进 Redis 黑名单
- 每次后台请求先检查黑名单

考虑点：

- **兼顾无状态与主动失效**
- **只给后台启用黑名单即可先满足生产诉求**
- **不需要一下子引入复杂的 refresh token 体系**

## 5. 权限模型：为什么是 RBAC + 菜单即权限标识？

当前关系模型：

```text
admin_user
  └── admin_user_role
        └── role
              └── role_menu
                    └── menu
```

这里的 `menu` 同时承担两层角色：

- 页面菜单节点
- 按钮/操作级权限标识（如 `system:role:create`）

这样设计的考虑点：

- 对管理后台来说，菜单和按钮权限天然相关
- 角色分配时只要分配菜单树即可，前端拿到菜单后也更容易渲染
- 不需要额外再造一套 `permission` 表，复杂度更低

当前的菜单类型：

- `1`：目录
- `2`：菜单
- `3`：按钮权限

## 6. 为什么角色和菜单管理要做这些校验？

### 角色编码唯一

考虑点：

- `code` 是系统内真正稳定的权限身份标识
- 名称可以改，但编码最好长期稳定
- 后续对接前端按钮、数据权限、系统配置时都可能依赖它

### 已绑定管理员的角色不可删除

考虑点：

- 避免删除后出现“管理员存在但角色关系悬空”
- 比起物理删除后再补数据，前置阻断更稳

### 菜单树防环

考虑点：

- 菜单是树结构，修改父节点时必须防止把节点挂到自己的子孙节点下
- 一旦形成环，树查询、前端渲染、递归构建都会出问题

## 7. 操作审计：为什么用 AOP，而不是每个 Controller 手写日志？

当前做法：

- 在写操作 Controller 上加 `@OperationLog`
- 切面统一记录模块、操作、URI、方法、参数、IP、耗时、状态

考虑点：

- **避免重复代码**：否则每个接口都得自己写一遍日志组装
- **更统一**：字段格式、脱敏规则、失败处理都集中管理
- **失败不影响主流程**：审计写入异常只记 warn，不阻断业务

当前还做了参数脱敏：

- 所有**名字里含 `password` 的字段**（`password`、`oldPassword`、`newPassword`）统一替换为 `***`

考虑点：

- 后台操作日志很容易进入文件、数据库、ELK，如果不先脱敏，密码会变成二次泄露源
- 这里不能用「字段名精确等于 password」的写法：`oldPassword` / `newPassword` 是后加的字段，
  精确匹配会漏掉它们，把明文密码写进 `operation_log`。当前实现用正则匹配：

  ```java
  Pattern.compile("\"([^\"]*password[^\"]*)\"\\s*:\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE)
  ```

  即「JSON 里 key 含 password 的字符串字段，值一律替换」，新增同类字段无需再改切面。

- 切面同时把 `MultipartFile` 排除在可序列化类型之外，避免导入接口把整个文件内容写进日志。

## 8. 初始化数据：为什么启动时自动创建 admin / admin123？

当前做法：

- 应用启动时，`AdminDataInitializer` 逐项「查不到就创建」（find-or-create）
- 首次启动自动创建：
  - 超级管理员 `admin / admin123`
  - `ADMIN` 角色
  - 系统管理菜单树（含用户管理 / 角色管理 / 菜单管理 / 操作日志等按钮权限）
  - 角色与菜单、管理员与角色关联
- 后续启动只补齐**缺失的**部分：新增的菜单、新增的按钮权限会自动写入老库，
  已存在的授权不会被覆盖，也不会重复插入

考虑点：

- **降低接入成本**：本地启动后马上可登录，不必手工插库
- **方便联调和演示**
- **幂等执行**：避免每次启动重复塞数据

> ⚠️ **这里踩过一个坑，值得记住**：最初的实现是「`admin_user` 表非空就整体跳过」。
> 这个写法在第一次启动时没问题，但**之后新增的菜单和按钮权限永远不会写进已有的库**——
> 结果是新加的 `@PreAuthorize("hasAuthority('...')")` 因为库里没有对应权限标识而永久 403，
> 而且现象很难定位（接口、代码都对，就是没权限）。
> 现在的实现以 `menu.permission_code` 作为幂等键逐项补齐，`ensureRoleMenus` 只增不减，
> 既保证幂等，又保证新权限能自动下发。

这个契约由 `AdminDataInitializerUpgradeIntegrationTest` 钉住，共 4 个用例：

| 用例 | 模拟的场景 | 断言 |
|------|-----------|------|
| `deletedPermission_shouldBeRestoredOnNextStartup` | 老库里缺少某条权限（连同授权） | 权限被补齐，且同一 `permission_code` 只有 1 条记录、授权不重复 |
| `missingRoleMenuGrant_shouldBeRestoredOnNextStartup` | 菜单在、授权丢了 | 授权被补回，菜单不被重复插入 |
| `repeatedStartup_shouldNotDuplicateSeedData` | 已初始化完成的库上重复启动 2 次 | `menu` / `role` / `role_menu` / `admin_user` / `admin_user_role` 行数全部不变 |
| `missingAdminRoleBinding_shouldBeRestoredOnNextStartup` | `admin` 的 ADMIN 角色绑定丢失 | 绑定被补齐 |

测试用**原生 SQL 物理删除**来造「老库」——逻辑删除的行仍留在表里，模拟不出真实的升级场景。

> 注意 `menu.permission_code` 上**没有唯一索引**（只有 `idx_menu_parent_id`），
> 所以「同一权限被重复插入」不会被数据库拦住，只能靠初始化的幂等逻辑保证；
> 这也是上面把 `count(permission_code) == 1` 作为断言的原因。
> 而 `role_menu` / `admin_user_role` 上都有唯一键，重复授权会直接抛异常。

上线时要注意：

- 初始密码只能用于第一次进入系统
- 实际生产建议首登强制改密，或通过部署脚本注入随机初始密码
- 管理员导入的默认密码由 `admin.import.default-password` 配置（默认 `123456`，可用环境变量
  `ADMIN_IMPORT_DEFAULT_PASSWORD` 覆盖）

## 9. 管理员账号管理：为什么只禁用，不提供删除接口？

当前做法：

- 提供 新增 / 修改 / 启用停用 / 重置密码 / 分配角色，**没有 `DELETE`**
- 需要「移除某人」时用停用（`status = 0`）

考虑点：

- `admin_user` 走的是**逻辑删除**（`is_deleted`），但 `username` 上的唯一索引
  **并不排除已逻辑删除的行**。也就是说：删掉 `zhangsan` 之后，这个用户名**永远无法再被使用**。
- 更糟的是这会让启动初始化器炸掉：`ensureAdminUser` 要保证 `admin` 存在，
  如果 `admin` 曾被逻辑删除，再插入就会撞唯一键。
- 从审计角度，管理员账号是**操作日志里的责任主体**。把它删掉会让历史日志失去归属，
  排查「这条记录是谁改的」直接断线。
- 所以对后台管理员来说，**「停用」在语义上完全覆盖「删除」**：不能登录、token 立即失效，
  但身份和数据都还在。

结论：与其提供一个会在数据层埋雷的删除接口，不如不提供。前端菜单里也不放删除按钮。

## 10. 禁用为什么要立刻生效，而不是等 token 自然过期？

JWT 是无状态的：签发之后，服务端默认无法收回。如果不做处理，「禁用某管理员」要等到他的
token 过期才真正生效——这中间的窗口期可能是几小时。

当前做法：

- `AdminJwtAuthenticationFilter` 在每次请求时**不是只解析 token 就完事**，而是拿着 token 里的
  用户名再调一次 `AdminUserDetailsService.loadUserByUsername(username)` 重新装载主体；
- `loadUserByUsername` 里加了状态判断：

  ```java
  if (adminUser.getStatus() == null || adminUser.getStatus() != 1) {
      throw new UsernameNotFoundException("管理员账号已被禁用: " + username);
  }
  ```

  过滤器捕获异常后 `clearContext()`，请求随即被判定为未认证 → 401。

考虑点：

- **代价是一次查库**：但后台是低频管理流量，这个开销完全可以接受；
- **换来的是权限实时性**：禁用、改角色、改权限都在下一次请求立即生效，
  不需要额外维护一套「版本号 / 强制下线」机制；
- 比引入 refresh token 或在线会话表要轻得多，符合当前阶段。

> 注意：这套机制依赖「每次请求都回查主体」。如果哪天为了性能把它改成纯 token 解析，
> 权限实时性就会丢失，必须同时补上别的失效手段。

## 11. Excel 导入：为什么用 POI 自己封装，而不是直接上 EasyExcel？

当前做法：

- 父 POM 引入 `org.apache.poi:poi-ooxml`（`poi.version = 5.4.1`）
- 在 `spring-shop-common` 里封装一个**很薄**的工具类 `ExcelSupport`：
  - `read(InputStream, maxRows)` → `List<ExcelRow>`（跳过表头、跳过全空行、超行数直接报错）
  - `write(sheetName, headers, rows)` → `byte[]`（生成模板）
  - `parseDecimal` / `parseInt` / `isBlankText` 等取值辅助
- 各业务模块只写「列 → 字段」的映射与校验，不碰 POI API

考虑点：

- **EasyExcel 更省事，但会引一层较重的封装**：它有自己的注解模型、监听器模型、
  以及一份独立的依赖树；对本项目「模块边界清晰、公共模块保持轻量」的取向不太合。
- **POI 是底层库，能力边界清楚**：我们只需要「读全部为字符串」和「写一个模板」这两件事，
  用 `DataFormatter` 把所有单元格当字符串读出来，就足以让业务层专注做校验。
- **统一入口便于加护栏**：行数上限、列数上限、空行处理、异常话术（加密文件 / 非 Excel 文件）
  都在 `ExcelSupport` 里收口，业务模块不需要各写一遍。
- 唯一要接受的是**它比较啰嗦**，所以封装得足够薄，把复杂度挡在 common 里。

导入的几个共性设计：

| 设计点 | 说明 |
|--------|------|
| 部分成功 | 合法行照常入库，非法行逐行返回 `{行号, 原因}`，不让一行脏数据废掉整份文件 |
| 行号可定位 | `ExcelRow.rowNum` 用**用户看到的 Excel 行号**（表头是第 1 行，数据从第 2 行起），报错信息可以直接对着表格找 |
| 先校验后占坑 | 唯一键（SKU 编码、用户名）在**所有字段校验通过之后**才标记占用，否则「因价格写错而失败的行」会把编码锁死，后面同编码的合法行被误报为重复 |
| 组级失败要铺满 | 商品导入按商品名分组，分类解析失败会让该商品的**每一行**都给出原因，否则运营看不到问题出在哪一行 |
| 模板即文档 | 每个导入接口都配 `GET .../import/template`，模板里带示例行（商品模板用两行同名商品演示「一个 SPU 多个 SKU」） |

## 四、接口总览

> 权限列是 `@PreAuthorize("hasAuthority('...')")` 里用的权限标识，也就是 `menu.permission_code`。
> 这些标识全部由 `AdminDataInitializer` 幂等注入，新增接口时记得同步加进去，否则永远 403。

### 1. 后台认证 `/api/admin/auth`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/login` | 匿名 | 用户名密码登录，返回 token + 主体信息 |
| POST | `/logout` | 匿名 | token 进 Redis 黑名单 |
| GET | `/me` | 需登录 | 当前管理员信息（含角色、权限标识） |

### 2. 个人中心 `/api/admin/profile`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| PUT | `/password` | **需登录即可，无权限标识** | 修改自己的密码，需校验原密码 |

> 两个刻意的设计：① 不放在 `/api/admin/auth/**` 下，因为该前缀在 `SecurityConfig` 里是 `permitAll`，
> 放进去会变成匿名可改密码；② 不加 `@PreAuthorize`，因为**新建的管理员可能还没有任何角色**，
> 若要求权限标识，他连自己的初始密码都改不了。

### 3. 管理员账号管理 `/api/admin/users`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/` | `system:user:list` | 分页查询，支持用户名 / 姓名 / 状态筛选 |
| GET | `/{id}` | `system:user:list` | 详情（含 roleIds / roleNames） |
| POST | `/` | `system:user:create` | 新增管理员（可同时指定角色） |
| PUT | `/{id}` | `system:user:update` | 修改姓名 / 手机号 / 状态 |
| PUT | `/{id}/status` | `system:user:update` | 启用停用 |
| PUT | `/{id}/password` | `system:user:reset` | 重置密码（管理员操作，不需原密码） |
| PUT | `/{id}/roles` | `system:user:assign` | 分配角色（全量覆盖） |
| POST | `/import` | `system:user:import` | Excel 批量导入 |
| GET | `/import/template` | `system:user:import` | 下载导入模板 |

**没有 DELETE**：原因见上文「## 9」。停用即等价于删除。

**不能对自己停用**：`updateStatus` / `update` 会校验目标 id 是否等于当前登录管理员，
命中则返回 `5008 ADMIN_SELF_OPERATION_FORBIDDEN`，防止把自己锁在系统外。

**为什么 `PUT /{id}` 不含密码和角色**：密码走 `/{id}/password`、角色走 `/{id}/roles`。
拆开是为了让「改个手机号」这种低危操作不会被顺带用来提权，权限边界更清楚。

**导入模板 6 列**（一行一个管理员）：

```
用户名* | 姓名 | 手机号 | 角色编码*(多个用逗号分隔) | 状态(1启用/0禁用) | 初始密码(留空用默认密码)
```

- 角色编码支持 `,` `，` `、` `;` `；` 和空白做分隔符（正则 `[,，、;；\s]+`），
  不要求运营记住某一种分隔符。
- 初始密码留空时用 `admin.import.default-password`（默认 `123456`，可用环境变量
  `ADMIN_IMPORT_DEFAULT_PASSWORD` 覆盖）；填写时长度至少 6 位。
- 角色编码必须已存在且启用，否则该行失败。
- 行数上限 **500**（比商品导入的 1000 更保守，因为每行都要额外做角色解析与密码加密）。
- 部分成功：合法行落库，非法行返回 `{rowNum, 原因}`。

### 4. 操作日志 `/api/admin/operation-logs`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/` | `system:log:list` | 分页查询，支持模块 / 用户名 / 操作 / 状态 / 时间区间筛选 |

- 只读，不提供删除和修改：审计日志可改就失去意义了。
- 查询接口本身**刻意不加 `@OperationLog`**，否则「查日志」这个动作会不断往日志表里写日志。
- 时间参数格式为 `yyyy-MM-dd HH:mm:ss`，排序固定 `create_time DESC, id DESC`。

### 5. 角色管理 `/api/admin/roles`

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/` | `system:role:list` |
| GET | `/all` | `system:role:list` |
| POST | `/` | `system:role:create` |
| PUT | `/{id}` | `system:role:update` |
| DELETE | `/{id}` | `system:role:delete` |
| POST | `/{id}/menus` | `system:role:assign` |
| GET | `/{id}/menus` | `system:role:list` |

### 6. 菜单管理 `/api/admin/menus`

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/tree` | `system:menu:list` |
| POST | `/` | `system:menu:create` |
| PUT | `/{id}` | `system:menu:update` |
| DELETE | `/{id}` | `system:menu:delete` |

### 7. 商品管理（spring-shop-product 模块，挂在后台链下）

| 方法 | 路径 | 权限 |
|------|------|------|
| GET/POST/PUT/DELETE | `/api/admin/categories/**` | `product:category:*` |
| GET/POST/PUT | `/api/admin/products/**` | `product:product:list / create / update` |
| PUT | `/api/admin/products/{id}/status` | `product:product:update` |
| POST | `/api/admin/products/import` | `product:product:import` |
| GET | `/api/admin/products/import/template` | `product:product:import` |

### 8. 订单管理（spring-shop-order 模块）

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/api/admin/orders` | `order:order:list` |
| PUT | `/api/admin/orders/{id}/ship` | `order:order:ship` |

### 9. 数据运营（spring-shop-stats 模块）

| 方法 | 路径 | 权限 |
|------|------|------|
| GET/POST | `/api/admin/stats/recall/**` | `stats:recall:build` |

## 五、数据库与迁移

管理后台对应迁移脚本：

- 主库：`spring-shop-web/src/main/resources/db/migration/V4__init_admin_schema.sql`
- 测试库：`spring-shop-web/src/test/resources/db/migration-test/V4__init_admin_schema.sql`

主要表：

- `admin_user`
- `role`
- `menu`
- `admin_user_role`
- `role_menu`
- `operation_log`

之所以主库和测试库各保留一套 V4，是因为 H2 对索引名全局唯一的限制比 MySQL 更严格，测试脚本需要做兼容处理。

## 六、当前实现的边界与后续补充建议

### 已交付

- ✅ **管理员用户管理**：新增 / 修改 / 启用停用 / 重置密码 / 分配角色 / Excel 批量导入（**无删除**，见「## 9」）
- ✅ **操作日志查询**：分页 + 多条件筛选，只读
- ✅ **个人中心改密**：校验原密码
- ✅ **商品管理后台**：分类、SPU、SKU、上下架，含 **Excel 批量导入**
- ✅ **订单管理后台**：订单查询、发货
- ✅ **数据运营**：加购未买召回圈人
- ✅ **权限实时性**：禁用 / 改角色下次请求即生效（见「## 10」）
- ✅ **鉴权失败的 HTTP 语义**：`@PreAuthorize` 拒绝返回 403 + `Result.fail(FORBIDDEN)`，
  不再被兜底成「系统内部错误」（见 AGENTS.md「HTTP 状态码与业务码的分工」）
- ✅ **初始数据的升级路径**：老库重复启动时幂等补齐缺失的菜单 / 权限 / 授权，
  新增权限能自动下发（见「## 8」，由 `AdminDataInitializerUpgradeIntegrationTest` 钉住）
- ✅ **权限码覆盖率**：从运行时容器扫描全部 `@PreAuthorize`，与 `menu` 表对账，
  断言「已注册 + 已授予 ADMIN」，把「新增接口忘了注册权限 → 永久 403」变成 CI 红灯
  （由 `AdminPermissionCoverageIntegrationTest` 钉住）

### 还没做

1. **更完整的安全能力**：
   - 首登强制改密
   - 定期改密
   - 登录验证码
   - IP 白名单 / 限流
   - refresh token / 单点登录策略
2. **管理员的「删除」诉求**：如果确实需要释放用户名，正确做法是
   **新增一个独立的「账号回收」流程**（先改名为 `zhangsan_deleted_20261003` 之类的墓碑名，
   再逻辑删除），而不是直接删行。当前刻意不做。
3. **导入的异步化**：现在导入是同步请求内完成，行数上限 1000（商品）/ 500（管理员）。
   如果将来要支持上万行，需要改成「上传 → 异步任务 → 结果下载」的模式。
4. **前端页面**：本文只覆盖后端接口，管理后台前端（菜单渲染、权限按钮、导入向导）不在本仓库。
5. **操作日志的归档**：`operation_log` 目前只增不清理，长期需要按月归档或分区。

## 七、一句话结论

当前这套实现是一个比较典型的**单体电商后端中的管理后台子模块**做法：

- 后端仍保持单体多模块，控制复杂度
- 前后台通过双过滤链和用户类型彻底隔离
- 权限中心、登录加固、审计日志这些生产常见能力已经就位

对这个项目来说，这比现在就拆成两个后端项目更稳，也更符合当前阶段的投入产出比。
