# MEMORY.md — spring-shop 项目长期记忆

> 只记「跨会话仍然成立」的项目约定与判断，不记流水账（流水账在 `YYYY-MM-DD.md`）。

## 缓存一致性：测试必须断言「行为」，不能只断言结果

**背景（2026-10-08）**：下单后购物车 Redis 缓存未失效的 bug（`OrderServiceImpl.create()`
直接用 `cartItemMapper.delete` 绕过 `CartServiceImpl`），
`GET /api/cart` 会在 7 天滑动 TTL 内一直返回已下单的条目。

**为什么它能漏到线上**：`OrderIntegrationTest` 与 `CartIntegrationTest` 都用
`@MockBean StringRedisTemplate` 把 Redis 整个 mock 掉（测试环境无 Redis）。
缓存永远 miss → 读路径永远回源 DB → **脏读在集成测试里根本不会出现**。
`OrderIntegrationTest` 里那条 `assertEquals(0, cartItems(token).size())` 断言，
在修复前后都是绿的。

**因此的项目约定**：

- 任何**缓存写入/失效**相关的改动，单测必须断言**「失效方法被调用」**
  （如 `verify(stringRedisTemplate).delete(RedisKeys.cart(userId))`），
  而不能只断言最终可观测结果 —— 后者会被 `@MockBean` 掩盖。
- 想验证真实的缓存一致性，只能**真连 Redis 实跑**（本机 MySQL + Redis 都在跑，
  起一个独立端口的实例做 A/B 对照是最省事的办法）。
- 参考实现：`OrderServiceImplTest#create_should_evict_cart_cache`。

## 缓存失效的责任边界

`CartServiceImpl` 自己的写路径（`add` / `updateQuantity` / `updateChecked` / `delete` /
`deleteChecked` / `clear`）都会维护 `RedisKeys.cart(userId)`；
**但绕过它、直接操作 `cartItemMapper` 的地方必须自己补失效**。
已知会绕过它的地方：`OrderServiceImpl.create()`（下单删勾选行）——
**已修并已合入 main**（`895b976`，合并提交 `10a9864`，2026-10-08）。
新增任何直接操作 `cart_item` 的代码时，先想一遍要不要失效 `cart:{userId}`。

## MCP 方案：已评估并否决（2026-10-08）

REST→MCP **不采用**，实验终止。**不要再评估或引入 MCP。**

理由：
1. 唯一「零代码 + 真 HTTP 链路」都成立的方案（实验二 mcp-link）是个 **Go 写的旁路进程**，
   与项目 Java / Spring Boot 技术栈不符，等于多一套异构运行时与运维负担。
2. 唯一同栈的方案（实验四 Spring AI 进程内）不经 HTTP 过滤链 → 拿不到 JWT 身份 →
   **写操作做不了**，只能暴露只读工具；且 `/sse` 无鉴权。属结构性限制。

完整实测数据与踩坑存档：`docs/superpowers/plans/2026-10-06-rest-to-mcp-evaluation.md`
（开头已标记「终止，勿执行」）。MCP 评估技能 `skills/spring-shop-mcp-eval/`
**已于 2026-10-08 按用户要求删除**（项目 skills 目录现只剩 `spring-shop-excel-task`）。

## 分支状态

`main` **领先 `origin/main` 5 个提交（未 push）**，`origin/main` 仍在 `f829967`
（2026-10-09 核对）。领先的 5 个提交就是本轮六项修复：
`839c507`（A4+A7）· `e221327`（A2）· `1d646f9`（A1）· `5a3e6cf`（B1）· `bf107f8`（A3）。
⚠️ 用户说的是「提交 main」，**没说 push**，所以只 commit 了。要推的话先问。

| 分支 | 内容 | 状态 |
|---|---|---|
| `fix/cart-cache-invalidate-on-order` | 购物车缓存失效修复（`895b976`） | ✅ **已合入 main**（合并提交 `10a9864`） |
| `feature/order-timeout-cancel` | 订单超时自动取消 | ✅ 已合入 main，分支冗余 |
| `feature/observability` | Actuator + traceId | ✅ 已合入 main，分支冗余 |
| `feature/admin-user-log-import` | 后台账号管理 + 日志查询 + Excel 导入 | ✅ 已合入 main，分支冗余 |

> 上述 4 个分支**本地与 remote 都还在**，均已合并、可清理（未获用户明确同意前不要删）。
> `feature/mcp-experiment`（实验四 Spring AI 进程内 MCP Server，`32fd2b5`）**已于 2026-10-08 删除**，
> Go 工具链（271MB）、Go 缓存（154MB）、mcp-link 产物（15MB）一并清理。
> 该分支**从未合入 main**，删除不影响 main。

## 用例数口径（易搞错，记一次）

**全量 `mvn test` = 324 个用例**：common 38 / user 17 / admin 28 / product 44 / cart 38 /
order 29 / pay 7 / stats 10 / web 113（2026-10-09 实测，BUILD SUCCESS）。
（2026-10-08 基线 287；本轮六项修复 +37 = common +21 / web +12 / product +4。）

⚠️ Maven 每个模块各打印一次 `Tests run: N`，**最后一行只是最后一个模块（web = 113）**，
不是全 reactor 总数。此前日志里「全 reactor 101 用例」的说法就是这么误读来的，**已更正**。
README 早期写的「255 用例」同样过时。

## 已知未修的缺陷（按优先级）

1. **Excel 异步任务框架的 14 条盲区**（详见 `2026-10-03.md`）。**高危四条已全部修完（2026-10-09）**：
   - ~~`params` 序列化失败会**静默导出全量**~~ ✅ **已修（2026-10-08）**：
     `writeParams` 改为抛 `EXCEL_TASK_PARAMS_INVALID(47)`，失败时任务行不落库。
   - ~~导入重跑语义~~ ✅ **已修（2026-10-09，`1d646f9`）**：用户选「**严格拒绝**」。
     商品名已存在即按商品粒度拒绝（**不覆盖、不新增**），提示写明「导入不会覆盖」与该去哪里改；
     重名校验排在分类校验**之前**（名字已存在时先报分类会把运营引到无关错处）。
     查重只看未删除商品（`is_deleted = 0`）——与 SKU 编码查重口径**刻意不同**：
     `name` 没有唯一索引，不存在「必须与索引口径一致」的硬约束。
     ⚠️ **这是应用层兜底，不是数据库约束**：查重与落库之间仍有极窄窗口，
     两个任务同时导入同一个新商品名仍可能各建一个 SPU。真正的保证需要给
     `product.name` 加唯一索引，但那与逻辑删除冲突（删掉的商品会永久占名，
     正如 `uk_sku_code` 现在的处境），且需先清理历史重名数据。
     **已把取舍写进代码注释，等用户拍板，不擅自加库表变更。**
   - ~~表头不校验按列下标取值会串列~~ ✅ **已修（`e221327`）**：
     `ExcelReadOptions.expectedHeaders` + `ExcelSupport.invokeHead` 逐列比对，
     失败时指出第几列 / 应当是什么 / 实际是什么。**必须遍历 expectedHeaders 而不是文件里出现的列**，
     否则发现不了「删掉了最后一列」。
   - ~~导出用 OFFSET 分页会重复/漏行~~ ✅ **已修（`bf107f8`）**：改 keyset 游标分页。
     `PageFetcher` 契约为 `(query, Long lastId, pageSize)`，四个导出统一「按 id 单列排序 + id < 游标」
     （管理员导出是正序，用 `>`）。**操作日志导出的排序已按用户选择改成纯 `id DESC`**
     （原 `(create_time DESC, id DESC)`）：`id` 自增、`create_time` 插入时写入且精度到秒 ⇒ 两者同序，
     行序变化不可观测。`OperationLogServiceImpl.buildWrapper` 因此拆成 `filterWrapper`，排序下沉到调用方。
2. ~~错误分页参数名被静默忽略~~ ✅ **已修（`5a3e6cf`）**：`PageParamGuardInterceptor`。
   只在「处理方法绑定了 `PageQuery` 子类」时生效——`limit` 在 stats 召回接口上是合法参数，全局拦会误伤。
   必须是**拦截器**而不是过滤器：拦截器抛的异常才走 `DispatcherServlet` 异常解析链 →
   `GlobalExceptionHandler` 统一成 `Result`；过滤器得自己手写 JSON。

### 仍未处理（用户 2026-10-09 明确「大件能力不需要」，留档别当待办）

A5/A6/A8/A9/A10~A16（Excel 框架其余盲区）· B2/B3 · C1 券体系+触达 · C2 `cart_add_log` 埋点 ·
C3 定时任务平台化 · C4 Docker 交付 · D 组基础设施 · E1 清理 4 个已合并的冗余分支。

## 写单测时的两个坑（2026-10-09 踩到，别再踩）

- **纯 Mockito 单测里 MP 的 lambda wrapper 渲染出的是属性名不是列名**：手工预热 `TableInfo`
  时没有 MP 的 `GlobalConfig`，`getSqlSet()` 会给出 `errorMsg=#{...}` 而非 `error_msg=#{...}`。
  ⇒ 断言写成 `sqlSet.replace("_","").toLowerCase().contains("errormsg")`，
  「列名到底对不对」交给 H2 集成测试（`ExcelTaskCasIntegrationTest`）。
- **`getParamNameValuePairs()` 是 public 的**，用它断言「恰好 N 个绑定值为 null」可以证明
  `wrapper.set(..., null)` 真的生效（`NOT_NULL` 策略下参数根本不会被注册）。
- **`-Dsurefire.failIfNoSpecifiedTests=false`** 才是正确属性名（不是 `-DfailIfNoSpecifiedTests`）。

