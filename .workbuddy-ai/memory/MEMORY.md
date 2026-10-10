# MEMORY.md — spring-shop 项目长期记忆

> 只记「跨会话仍然成立」的项目约定与判断。流水账在 `YYYY-MM-DD.md`。

## 接口文档（Swagger / OpenAPI）

- **没有静态文档文件**（仓库里没有 `openapi.json` / `swagger.yaml` 之类的产物）。
  文档由 springdoc **运行时从代码注解生成**：`/v3/api-docs`、Swagger UI `/swagger-ui.html`。
  ⇒ 「更新 swagger 文档」= **改代码注解**，没有别的文件要同步。
- 强约束（AGENTS.md「接口文档规范」）：Controller 类标 `@Tag`，**每个映射方法**标
  `@Operation(summary)` + `@ApiResponse(200, description)`；GET 的 POJO 查询参数必须
  `@ParameterObject`；路径变量用 `@Parameter(description)`；DTO/VO **类与每个字段**标 `@Schema`。
- 401/403/500 的中文描述由 `OpenApiConfig` 的 `OpenApiCustomizer` 统一补，**不要逐个 Controller 标**。
- **2026-10-10 全仓核对**：97 个 `@*Mapping` 方法**全部**带 `@Operation`；24 个 Controller 全部带 `@Tag`；
  本轮新增的 `Inventory*/Brand*/Attribute*/StatsTaskLog*` DTO/VO 全部带 `@Schema`。⇒ 文档是新的。
- 自检命令：`curl -s http://localhost:6001/v3/api-docs` 检索 `description` 是否有空白/英文默认值。

## 🔴 商品唯一性口径（用户 2026-10-09 定稿）

**前提：单店模式**（唯一性范围全局）。

| 项 | 规则 |
|---|---|
| **唯一性单元** | **(商品名称, 规格)** —— SKU 粒度，**不是** `sku_code` |
| **判定范围** | **仅未删除记录**（`is_deleted = 0`） |
| **同名 + 不同规格** | **合法**（同一 SPU 下的两个 SKU） |
| **同名 + 同规格** | 拒绝（未删除范围内不允许两条） |
| **`sku_code`** | **保留全局唯一**（`uk_sku_code` 不动），两套口径并存 |
| **删除后重新新增同名同规格** | **新增一条新记录（新 id）**，不复用旧记录 |

- 商品上架(1)/下架(0)：**只有下架商品可删**，上架删除拒绝；删除是**逻辑删除**（`is_deleted = 1`）。
- ⚠️ **规格必须规范化**（否则唯一性被绕过）：`颜色:黑;尺寸:L` 与 `尺寸:L;颜色:黑` 会被当成两个商品。
  trim → 统一分隔符 → 每段 trim → **按段排序** → 空/null 归一为空串。实现见 `SkuSpecNormalizer`。
- **已落地**：`validateIdentitiesUnique`（2015）；校验顺序 `SKU空 → sku_code → (名称,规格) → 分类`
  （唯一性**必须排在分类之前**，否则把运营引到无关错处）；导入按 `(名称,规格)` 逐行判定，
  同名不同规格**复用 productId 只追加 SKU**；新增 `DELETE /api/admin/products/{id}`（仅下架可删，否则 2016）。
- **两个取舍**：① 查重 SQL `selectOccupiedProductSpecs` 用 **LEFT JOIN**，`s.is_deleted = 0` 必须写在
  **`ON`** 里（写进 `WHERE` 会退化成 INNER JOIN），代价是消费方**必须跳过 `specs == null`** 的行。
  ② 导入复用 SPU 时**商品级字段（分类/副标题/主图）一律忽略**，只追加 SKU。
- **错误码**：`PRODUCT_IDENTITY_DUPLICATE(2015)` / `PRODUCT_NOT_OFF_SHELF(2016)`。

### 三个同源 bug（2026-10-09 已全部修复，留档）

根因：`ProductManageServiceImpl.saveSkus()` 修改商品时「**删掉全部 SKU 再重新插入**」。
① 逻辑删除后 `sku_code` 仍占 `uk_sku_code` ⇒ 修改必然 `DuplicateKeyException` 500；
② `ProductSkuItem.stock` 无 `@NotNull` + `requireNonNullElse(...,0)` ⇒ 不传 stock 静默清零
（V9 后还会把新建的 `inventory` 行建成 0，商品直接不可售）；
③ 删旧插新换掉所有 `sku_id` ⇒ `cart_item` / `order_item` 指向旧 SKU，购物车型号查不到。
**为什么没被发现**：纯 Mockito 单测 + 集成测试里**没有一条用例打过 `PUT /api/admin/products/{id}`**。
**修法**：`saveSkus` 改「**按规范化规格 upsert、保留 `sku_id`**」；新增时 stock=null 视为 0，
**修改时 stock=null 保持原值**。回归防线 `ProductUpdateIntegrationTest`（12 用例）。

## 🔴 库存模型现状（V9 起 `inventory` 是唯一真相源）

> 历史方案（在 `product_sku` 上加 `locked_stock` 列、建 `stock_log` 表）**已作废**，
> 实际落地是 `inventory` 独立表 + `inventory_log`。**不要按旧方案写代码。**

- **三量语义**：`inventory.stock` = 在库实物量；`locked_stock` = 未付款订单锁定；
  **`available = stock − locked_stock`**（派生，不落库）。
- **状态机**：下单只 **lock**（在库不变、锁定 +）；支付 **outbound**（在库与锁定同时 −）；
  取消/超时 **release**（锁定 → 可售）。
- **读路径一律用 `InventoryService.available()/availableMap()`**（= `max(stock−locked, 0)`）；
  **库存行缺失返回 0 而非抛异常**（fail-closed，一条脏数据不该把详情页打成 500）。
- 业务侧锁/出/释**全是条件更新**：0 行 = 条件不满足 ⇒ 抛业务异常。库存域错误码 2030/2031
  在 `OrderServiceImpl.lockStock` 里**映射成订单域 4004**（保持前端契约不变）。
- **`product_sku.stock` 已由 V10 删除**，实体上不再有库存字段。订单链路完全不碰它。
- 库存变更一律经 `InventoryService`（条件更新 + **同事务落 `inventory_log`**），保证「改了库存必有账」。
- **顺手修的并发缺陷**：`pay()`/`cancel()` 由 `requireOrder + updateById` 改为条件更新
  （`OrderMapper.markPaid` / `cancelIfPendingOrder`）⇒ 重复支付不重复出库、重复取消不重复释放。
- **测试侧教训（本轮最大工作量）**：搬家后**所有「手工 INSERT 老表数据」的测试都成了假数据**
  —— `Order/Cart/PayIntegrationTest` 的 `createSku(...)` 绕过业务创建路径，**必须补插 `inventory` 行**，
  否则可售量 = 0，加购/下单全报「库存不足」（看着像业务 bug，其实是测试数据缺失）。
  ⇒ 找全测试的姿势：按老列 setter（`setStock(`）**全仓库 grep**。
- **未逐条确认的默认值**：出库时机 = **支付成功**（不是发货）；`specs` → 属性三表历史回填**暂缓**。

### V9/V10 迁移的两条硬规则

1. **已发布的 Flyway 脚本一个字节都不能改**（见下方 Flyway 节）。
2. **DROP 列前必须先跑一致性检查**（结果须 0 行）：
   ```sql
   SELECT s.id, s.sku_code, s.stock, i.stock FROM product_sku s
   LEFT JOIN inventory i ON i.sku_id = s.id
   WHERE s.is_deleted = 0 AND (i.id IS NULL OR s.stock <> i.stock);
   ```
- 写迁移的三个坑：① `VALUE` 是 H2 关键字 ⇒ 列名用 `attr_value`；
  ② `migration-test` 副本的 `ALTER` 要 H2 兼容（不用 `AFTER`/`ADD KEY`，拆成 `ADD COLUMN` + `CREATE INDEX`）；
  ③ **索引名前缀化、全局唯一**，主脚本与副本一致（CI 红线）。
- 建 `inventory` 行必须在 SKU 拿到自增 id **之后**；`insertBatch` 不回填主键 ⇒ **按 `sku_code` 回查**。

## 缓存一致性：测试必须断言「行为」，不能只断言结果

**背景（2026-10-08）**：下单后购物车 Redis 缓存未失效（`OrderServiceImpl.create()` 直接
`cartItemMapper.delete` 绕过 `CartServiceImpl`），`GET /api/cart` 在 TTL 内一直返回已下单条目。
**漏到线上的原因**：集成测试用 `@MockBean StringRedisTemplate` 把 Redis 整个 mock 掉 ⇒
缓存永远 miss ⇒ 读路径永远回源 DB ⇒ **脏读在集成测试里根本不会出现**。

**项目约定**：任何**缓存写入/失效**改动，单测必须断言**「失效方法被调用」**
（`verify(stringRedisTemplate).delete(RedisKeys.cart(userId))`），不能只断言最终结果。
参考：`OrderServiceImplTest#create_should_evict_cart_cache`。

### 缓存失效的责任边界

- **购物车缓存 `cart:{userId}`**：`CartServiceImpl` 自己的写路径都会维护；
  **绕过它直接操作 `cartItemMapper` 的地方必须自己补失效**。已知绕过点：`OrderServiceImpl.create()`（已修）。
- **商品详情缓存 `product:detail:{productId}`**：VO 里带每个 SKU 的**可售量**与商品**销量**，
  ⇒ **凡改变可售量或销量的写路径都必须失效它**。

| 写路径 | 谁失效 | 状态 |
|---|---|---|
| `ProductManageServiceImpl.update` / `updateStatus` | 自己 | ✅ |
| `OrderServiceImpl.create()`（lock + increaseSales） | 自己 | ✅ |
| `OrderServiceImpl.cancel()` / `systemCancel()`（release + decreaseSales） | 自己 | ✅ |
| `InventoryServiceImpl.adjust()`（后台调在库量） | 自己 | ✅（2026-10-09 补） |
| `OrderServiceImpl.pay()` / `markPaid()`（outbound） | **不需要** | ✅ 刻意不做 |

⚠️ **`outbound` 不需要失效是不变量不是遗漏**：可售量 = `stock − locked`，出库时两者同时减 q
⇒ 差值恒等；支付也不改销量。**不要为了「对称」加失效**（会给支付热路径引入 Redis 依赖）。
已用 `InventoryServiceImplTest#outbound_shouldNotEvictDetailCache_becauseAvailableIsInvariant` 钉住。

## 后台只读查询接口的标准配方（已出现 4 次，照抄）

`PageQuery` 子类（`current`/`size` + 业务过滤字段）→ Service 返回 `PageResult<XxxVO>`
（**不返回 entity**，AGENTS 红线）→ `@ParameterObject @Valid` 绑定 → `@PreAuthorize` 权限码
→ **在 `AdminDataInitializer.ensureMenu` 幂等注册**（漏了就是永久 403）。

- **日期区间一律用半开区间** `[startDate 00:00, endDate+1 00:00)`（写 `23:59:59` 要猜列精度，还会漏最后一秒）。
- **过滤列挑有索引的那个**。`stats_task_log` 只有 `(task_name, start_time)` ⇒ 按执行时间过滤而非 `stat_date`。

## Flyway：已应用的迁移脚本一个字节都不能改

`validate-on-migrate=true`（默认），校验和存 `flyway_schema_history`。
**改已应用脚本 ⇒ 所有跑过它的库「启动即校验失败」**（2026-10-09 实际踩到：V9 应用后被重写，
把 `value` 改成 `attr_value`，dev 库直接起不来）。正确做法是**纯增量**（V2 建列 → V9 回填 → V10 删列）。

> 记忆点：**脚本 mtime 比 `installed_on` 晚 ⇒ 一定被改过。**

- **诊断库结构与脚本不一致**：建全新对照库跑完整迁移，再逐表 diff（`mysqldump --no-data` 规范化后 `diff -u`），
  不用启动应用。噪声：`flyway_schema_history` 自身 collation 差异，忽略。
- **修复三步**：① 把问题库结构改成与新脚本一致；② `flyway repair`（重写校验和，**会改写迁移历史，先跟用户说明**）；
  ③ `flyway migrate`。⚠️ **只 repair 不修结构是错的**（校验绕过但列名依旧旧，运行时才炸）。
- **不启动 Spring 容器跑迁移**：`mvn dependency:build-classpath` + `java -cp` 跑一个 Flyway Java API 单文件。
- ⚠️ **`MAX(version)` 陷阱**：`version` 是 VARCHAR，`MAX()` 走字典序 ⇒ `'9' > '10'`。
  查最新版本一律 `ORDER BY installed_rank DESC LIMIT 1`。
- **dev 库当前在 V10**（rank 11，`success=1`），`product_sku.stock` 已删，与「全新 V1→V10」库 **0 差异**。
  生产库尚未接触。

## 写单测/跑测试时的坑（别再踩）

- **⚠️ 单模块跑测试必须带 `-am`**，否则从 `~/.m2` 解析旧快照 jar，症状是一堆**毫不相干的
  `NoSuchMethodError` / `NoSuchFieldError`**，极易误判成「自己改坏了 common」。或直接跑全量 reactor。
- **⚠️ `mvn test-compile` 可能是假成功**：增量编译器跳过未变更类，**真编译错误也报 BUILD SUCCESS**。
  改完测试源码一律用 **`mvn clean test-compile`**。
- **纯 Mockito 里服务层只要构造 lambda wrapper，就必须 `@BeforeAll` 预热 `TableInfo`**，
  否则抛 `can not find lambda cache for this entity`。实证：同类同实体，走 `eq()` 的绿、走 **`in(...)`** 的炸。
  写法对齐 `ProductQueryServiceImplTest#warmupMybatisPlusLambdaCache`。
- **⚠️ MP 3.5 参数绑定是惰性的：读 `getParamNameValuePairs()` 前必须先调 `getSqlSegment()`**。
  否则 `ArgumentCaptor` 抓到的 wrapper 永远是空 Map，症状 `expected:<1> but was:<0>`（像「条件凭空消失」）。
  它与上面的 `TableInfo` 预热是两件事，缺一不可。
- **MP lambda wrapper 在纯单测里渲染的是属性名不是列名**（无 `GlobalConfig`）⇒ 断言用
  `sqlSet.replace("_","").toLowerCase().contains("errormsg")`，「列名对不对」交给 H2 集成测试。
- **`verify(mapper).insert(any())` 编译不过**（`insert(T)` 与 `insert(Collection<T>)` 歧义）
  ⇒ 写 `insert(any(XxxEntity.class))`。
- **⚠️ Mockito 默认返回值会「静默」改变结论**：补了 `@Mock` 却忘 stub 才是阴的 ——
  返回 `Map` → 默认空 Map ⇒ 用例**悄悄变绿**；返回 `int` → 默认 0 ⇒ 「本该成功」的用例**静默变红**
  （症状 `expected BusinessException but was NullPointerException`）。新增依赖后逐个确认哪些方法要 stub。
- **⚠️ 改校验顺序会顺手制造 `UnnecessaryStubbing`**（严格模式下「桩没被用到」直接红）
  ⇒ 调整校验顺序时**同步清理下游用例里失效的桩**。
- **测试里的「不该发生」要用 `never()` 显式钉住**（如复用已有 SPU 时
  `verify(productMapper, never()).insertBatch(any())`），只断言结果会被「碰巧也建了一个」蒙混。
- **新增 `@PreAuthorize` 权限码必须同步注册**：`AdminPermissionCoverageIntegrationTest` 反射扫出
  容器里所有 authority 与 `menu` 表对账，漏注册/漏授权直接红。注册点 `AdminDataInitializer#initMenus`，
  幂等键 `permission_code`。
- **`-Dsurefire.failIfNoSpecifiedTests=false`** 才是正确属性名（不是 `-DfailIfNoSpecifiedTests`）。
- `getParamNameValuePairs()` 是 public 的，可断言「恰好 N 个绑定值为 null」证明 `set(..., null)` 生效。

## 用例数口径（易搞错）

**全量 `mvn test` = 424 个用例**（2026-10-09 实测，BUILD SUCCESS）：
common 38 / user 17 / admin 28 / product 95 / cart 38 / order 33 / pay 7 / stats 10 / web 143。

⚠️ Maven 每个模块各打印一次 `Tests run: N`，**最后一行只是最后一个模块（web = 143）**，不是全 reactor 总数。
README 早期写的「255 用例」已过时。

## 分支与提交状态（2026-10-10 核对）

- **`main` 与 `origin/main` 完全同步（0 ahead / 0 behind，HEAD = `11d2889`）**，工作区 clean。
  此前记录的「领先 3 个提交未 push」**已作废** —— 已全部 push。
- 🔴 **任何分支永不删除（本地 + 远端都不删）**，无论是否已合并。**也不得再把「清理分支 /
  冗余分支 / 分支待清理」写进任何计划、清单、建议或决策点**（用户 2026-10-09 明确要求）。
- 现有 4 个功能分支（**均已合入 main，仅作事实记录，不含任何清理建议**）：
  `fix/cart-cache-invalidate-on-order` · `feature/order-timeout-cancel` · `feature/observability` ·
  `feature/admin-user-log-import`。

## 已收口 / 已否决（勿重启）

| 项 | 状态 |
|---|---|
| 商品唯一性规则 + 3 个同源 bug + 删除接口 | ✅ 已落地 |
| V10 删除 `product_sku.stock` | ✅ 代码侧已落地（dev 库已执行；生产库未接触） |
| 定时任务执行日志查询 `GET /api/admin/stats/task-logs` | ✅ 已落地 |
| Excel 框架高危四条盲区 | ✅ 已全部修完（params 校验 47 / 导入严格拒绝 / 表头校验 / keyset 分页） |
| 错误分页参数名被静默忽略 | ✅ 已修（`PageParamGuardInterceptor`，**必须是拦截器不是过滤器**） |
| **REST→MCP** | ❌ **已否决，勿重启**（Go 与 Java 栈不符；进程内方案拿不到 JWT 做不了写） |
| **环境统一（`.mvn/wrapper` + `.java-version`）** | ❌ **已否决**（用户原话「不做，去掉这个任务」） |
| `stock_log` 表（原项 3 方案） | ❌ 已被 V9 的 `inventory_log` 取代，**不要重启** |
| 失败告警（项 1b） | ❌ 不做（缺外部 webhook，做了也是假告警） |

**仍未处理（用户明确「大件能力不需要」，留档别当待办）**：Excel 框架其余盲区 · 券体系+触达 ·
`cart_add_log` 埋点 · 定时任务平台化 · Docker 交付 · D 组基础设施。
