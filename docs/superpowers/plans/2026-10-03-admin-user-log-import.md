# 后台账号管理 + 操作日志查询 + 商品/管理员 Excel 导入

**Goal:** 补齐管理后台三块缺口——管理员账号管理（增改/启停/重置密码/分配角色，**不含删除**）、操作日志查询接口，以及商品与管理员两个 Excel 批量导入（**部分成功**语义），配套单测与 H2 集成测试全绿。

**Architecture:** 账号管理与日志查询落在既有 `spring-shop-admin` 模块（复用双过滤链 + `@PreAuthorize` 菜单权限）；商品导入落在 `spring-shop-product`；Excel 能力以**薄封装**形式放在 `spring-shop-common`（POI），业务模块不直接引 POI。不新增模块、不改表结构。

**Tech Stack:** Java 21 + Spring Boot 3.5.16 + MyBatis-Plus 3.5.17 + Spring Security(JWT) + Apache POI 5.4.1 + JUnit5 + Mockito + H2

---

## 一、关键设计决策

| 决策 | 说明 |
|------|------|
| **不提供管理员删除接口** | `admin_user` 走逻辑删除，但 `username` 唯一索引**不排除已删行** → 用户名永久占用，且会让启动初始化器撞唯一键；管理员又是审计日志的责任主体。用「停用」等价替代。详见 `docs/admin-module.md` §9 |
| **禁用要立即生效** | `AdminJwtAuthenticationFilter` 每次请求都回查 `loadUserByUsername`，其中新增 `status != 1` 判断抛 `UsernameNotFoundException` → 过滤器 clearContext → 401。不引入 refresh token / 在线会话表 |
| **密码与角色拆成独立端点** | `PUT /{id}` 只改姓名/手机号/状态；密码走 `/{id}/password`，角色走 `/{id}/roles`。避免「改个手机号」被顺带用于提权 |
| **改自己密码的端点不加 `@PreAuthorize`** | 新建管理员可能尚无任何角色，若要求权限标识则连初始密码都改不了。同时**不能**放在 `/api/admin/auth/**` 下（该前缀 permitAll，会变成匿名可改密） |
| **`@PreAuthorize` 拒绝必须映射为 403** | 原实现被 `GlobalExceptionHandler` 的 `Exception` 兜底吞成「系统内部错误 500」。新增 `SecurityExceptionHandler`（web 模块，`@Order(HIGHEST_PRECEDENCE)`）返回 403 + `Result.fail(FORBIDDEN)` |
| **`SecurityExceptionHandler` 放 web 不放 common** | `common` 刻意不依赖 spring-security；且方法级鉴权异常发生在 Controller 调用期，不会经过 `ExceptionTranslationFilter`，只能由 `@RestControllerAdvice` 兜住 |
| **初始化器改为幂等 find-or-create** | 原「表非空就整体跳过」导致**新增权限永远进不了老库**，接口稳定 403。改为以 `menu.permission_code` 为幂等键逐项补齐，`ensureRoleMenus` 只增不减 |
| **导入用 POI 薄封装，不用 EasyExcel** | 只需「全按字符串读」+「写模板」两件事；`ExcelSupport` 统一收口行数/列数上限、空行处理、异常话术 |
| **SKU 占用查询要对齐索引口径** | `uk_sku_code` 不排除逻辑删除，故用裸 SQL `selectOccupiedSkuCodes`，不能用 lambda 查询（会漏掉已删行 → insert 阶段撞唯一键 500） |
| **组级失败要逐行铺开** | 商品按名称分组，分类解析失败 → 该商品**每一行**都返回原因，否则运营以为只改一行即可 |
| **先校验后占坑** | 唯一键（SKU 编码、用户名）在**所有字段校验通过之后**才标记占用，否则「价格写错而失败的行」会把编码锁死 |
| **无 Flyway 变更** | 本次不涉及表结构；`operation_log` / `admin_user` 等表已在 V4 脚本中 |
| **无 SecurityConfig 变更** | 新接口都在 `/api/admin/**` 或已受保护路径下，沿用既有双过滤链 |

## 二、接口清单

| 方法 | 路径 | 权限 |
|------|------|------|
| GET / GET{id} | `/api/admin/users[/{id}]` | `system:user:list` |
| POST | `/api/admin/users` | `system:user:create` |
| PUT | `/api/admin/users/{id}` | `system:user:update` |
| PUT | `/api/admin/users/{id}/status` | `system:user:update` |
| PUT | `/api/admin/users/{id}/password` | `system:user:reset` |
| PUT | `/api/admin/users/{id}/roles` | `system:user:assign` |
| POST / GET | `/api/admin/users/import[/template]` | `system:user:import` |
| GET | `/api/admin/operation-logs` | `system:log:list` |
| PUT | `/api/admin/profile/password` | 仅需登录（无权限标识） |
| POST / GET | `/api/admin/products/import[/template]` | `product:product:import` |

> 无 `DELETE /api/admin/users/{id}`，这是设计而非遗漏。

## 三、文件清单

**依赖与错误码**
- Modify: `pom.xml`（`poi.version=5.4.1` + dependencyManagement）
- Modify: `spring-shop-common/pom.xml`（引入 `poi-ooxml`）
- Modify: `ResultCode.java`（新增 2020、5006~5009）
- Modify: `GlobalExceptionHandler.java`（`MaxUploadSizeExceededException` → 可读提示）

**common 新增（无业务逻辑）**
```
spring-shop-common/.../common/excel/
├── ExcelSupport.java       # read/write/parseDecimal/parseInt，行数列数上限与异常话术收口
├── ExcelRow.java           # rowNum 用 Excel 可见行号（表头为 1，数据从 2 起）
├── ExcelReadException.java # extends IOException，区分「我们的友好话术」与 POI 原始异常
└── ImportError.java        # {rowNum, message}，两个导入共用
```

**admin 新增/修改**
- Create: `controller/{AdminUserController,OperationLogController,AdminProfileController}.java`
- Create: `service/{AdminUserService,AdminUserImportService,OperationLogService}.java` + `impl/*Impl.java`
- Create: `dto/{AdminUserPageQuery,AdminUserCreateRequest,AdminUserUpdateRequest,AdminPasswordResetRequest,AdminRoleAssignRequest,AdminChangePasswordRequest,OperationLogPageQuery}.java`
- Create: `vo/{AdminUserVO,AdminUserImportResultVO,OperationLogVO}.java`
- Modify: `config/AdminDataInitializer.java`（幂等 find-or-create + 新菜单权限）
- Modify: `service/AdminAuthService(+Impl)`（新增 `changePassword`）
- Modify: `security/AdminUserDetailsService.java`（`status != 1` 抛异常）
- Modify: `aspect/OperationLogAspect.java`（脱敏改正则覆盖 `*password*`；排除 `MultipartFile`）

**product 新增/修改**
- Create: `product/service/ProductImportService.java` + `impl/ProductImportServiceImpl.java`
- Create: `product/vo/ProductImportResultVO.java`
- Modify: `product/mapper/ProductSkuMapper.java`（`selectOccupiedSkuCodes`）
- Modify: `controller/admin/AdminProductController.java`（`/import`、`/import/template`）

**web**
- Create: `web/exception/SecurityExceptionHandler.java`（403 映射）
- Modify: `resources/application.yml`（multipart 上限 + `admin.import.default-password`）

## 四、任务分解

- [x] **Task 1 摸清现状**：确认 admin 无账号管理/日志查询接口、无 Excel 能力、初始化器幂等性缺陷 → 记录缺口清单
- [x] **Task 2 接入 POI**：父 pom 加版本管理 → common 引依赖 → 写 `ExcelSupport`/`ExcelRow`/`ImportError`/`ExcelReadException` → `mvn -q compile`
- [x] **Task 3 错误码 + 初始化器**：ResultCode 加码 → `AdminDataInitializer` 改幂等并注册新菜单权限 → 编译通过
- [x] **Task 4 账号管理**：DTO/VO/Service/Controller → 含「不能对自己停用」（5008）与用户名唯一（5006）
- [x] **Task 5 日志查询**：只读分页 + 多条件筛选；接口本身不加 `@OperationLog`
- [x] **Task 6 管理员导入**：6 列模板、角色编码多分隔符、默认密码可配、部分成功
- [x] **Task 7 商品导入**：10 列模板、同名聚合成一个 SPU 多 SKU、分类歧义整组失败、价格精度校验
- [x] **Task 8 补测试 + 全量验证**：3 个单测类 + 2 个集成测试类 → `mvn clean verify` **BUILD SUCCESS（243 tests）**
- [x] **Task 10 真机 multipart 补测（后续追加）**：新增 `ProductImportMultipartIntegrationTest`（`RANDOM_PORT` + 真实 Tomcat，5 用例），
      抓出并修复 `server.tomcat.max-swallow-size` 导致的超限上传断连问题 → **248 tests 全绿**
- [x] **Task 9 文档**：`AGENTS.md`（错误码表 + HTTP 状态码分工）、`docs/admin-module.md`（接口总览 + 设计章节 §9~§11 + 缺口清单）、`docs/product-module.md`（§5.4 导入设计）、本计划

## 五、验证要点

```bash
mvn -pl spring-shop-admin -am test -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl spring-shop-product -am test -Dsurefire.failIfNoSpecifiedTests=false
mvn clean verify        # 等价 CI，实测 248 tests / BUILD SUCCESS
```

- 单测沿用 MyBatis-Plus 预热：`@BeforeAll` 调 `TableInfoHelper.initTableInfo(AdminUser/AdminUserRole/Role)` 等，否则 lambda 在 Mockito 打桩前抛 `can not find lambda cache`
- 集成测试类头 4 件套：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`，外加 `@MockBean StringRedisTemplate`
- 导入单测**真的生成 xlsx**（`ExcelSupport.write` → `MockMultipartFile`），顺带覆盖读写双向
- 守门测试：「禁用后已签发 token 立即 401」「新建管理员改自己密码可成功」「导入部分成功且失败行号可定位」「无权限返回 403 而非 500」

## 六、暂不做（YAGNI）

管理员删除/账号回收流程、首登强制改密、登录验证码、IP 白名单、refresh token、导入异步化（上万行场景）、操作日志归档、product/order 后台接口的审计日志（需在 admin 侧扩 AOP 切点）。
