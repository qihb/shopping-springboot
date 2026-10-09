# 下一轮收尾计划（三项）— 待拍板

> **状态**：**只出计划，未落地任何代码**（2026-10-09 13:20 编写，14:05 修订）。
> **背景**：上一轮用户选定的六项修复（A1/A2/A3/A4/A7/B1）**已全部实现、测试全绿、并已 push 到
> `origin/main`**（HEAD = `ad1cbdb`，工作区 clean）。用户 2026-10-09 明确「大件能力不需要」，
> 因此本轮只覆盖**低成本、零资损风险**的收尾项。
> **前置文档**：`2026-10-03-scheduled-task-platform-options.md`（大件，本轮不做）、
> `2026-10-02-cart-recall-coupon.md`（Phase 2/3，本轮不做）。
> **执行方式**：每项 = 一个独立提交（可拆 PR）；三项各自独立，项 3 与项 2 有配合点
> （项 3 的「后台调整库存写流水」落在项 2 改造后的 `saveSkus` 上），但**提交可分开**。
> **修订记录**：
> ① 原「环境统一（`.mvn/wrapper` + `.java-version`）」一项 **已按用户 2026-10-09 13:42
> 的要求移除，不做**（见 §4）。
> ② 项 2 于 2026-10-09 13:55 **由「唯一索引取舍」升级为「商品唯一性规则落地」**——
> 用户定稿了唯一性口径（**(商品名称, 规格)**，未删除范围内唯一）、状态与删除规则
> （仅下架可删、逻辑删除），并拍板走**应用层查重**。原四个索引方案作废。
> ③ 项 3（库存流水表 `stock_log`）于 2026-10-09 14:05 新增——用户先问「项目里有没有库存表」，
> 核实后确认**无独立库存表**（`stock` 仅存在于 `product_sku`），随后拍板**单仓 + 新增流水表**，
> 并同意把项 2 发现的三个同源 bug 一并修。
> ④ ⚠️ **项 3 已于 2026-10-09 15:22 被「数据模型对齐」工作取代，勿再按本节实施**：
> 用户给出目标数据模型（`inventory` **独立表，sku_id 唯一**）并要求对齐落地，
> 因此**「在 `product_sku` 上加 `locked_stock` 列」的设计作废**，改为独立表；
> 流水表由 `stock_log` 更名为 **`inventory_log`**。已通过 **Flyway V9** 落地建表 + 实体
> （见 `.workbuddy-ai/memory/MEMORY.md`「数据模型对齐 V9」一节）。
> 本节保留仅为记录决策演进，**表名 / 方案以 V9 为准**。

---

## 0. 结论先行

> **分支一律不删，也不再列为待办**（用户 2026-10-09 明确要求）——详见 §1「分支策略」。

三件事：

| 序 | 事项 | 成本 | 风险 | 是否需改代码 | 状态 |
|---|---|---|---|---|---|
| 1 | 定时任务可观测补口 | 半天 | 低（纯新增只读接口） | ✅ | ✅ **1a 已落地（2026-10-09）**；告警（1b）**不做**（缺外部 webhook，见下方说明） |
| 2 | 商品唯一性规则落地（(名称,规格) 未删除范围内唯一 + 仅下架可删）**+ 修三个同源 bug** | 1.5~2 天 | **中高**（改导入语义 + 改商品保存策略，影响面最大） | ✅ | ✅ **已落地（2026-10-09，商品域收口）**，见 §2 项 2 顶部说明 |
| 3 | 库存流水表 `stock_log`（单仓，append-only） | 1 天 | 中（**新增 Flyway V9** + 改订单扣减链路） | ✅ | ⚠️ **已被 V9/V10 取代**（`stock_log` → `inventory_log`，独立 `inventory` 表），**勿按本节实施** |

**我的建议**：三项都具备开工条件。推荐顺序 **2 → 3 → 1**：

- **先做项 2**：它修的是「运营手工新增就能造重复商品」+「修改商品必然 500 / 库存被清零」
  —— 这些是**每天都在发生**的业务正确性问题。
- **再做项 3**：库存流水要与项 2 的商品保存改造配合（后台改库存要写流水），
  且它是项 2「仅下架可删」的配套可观测能力。
- **最后做项 1a**：可观测性，不紧急。

**前置动作**（项 2 动手前）：先跑一次 §2 项 2 末尾的「现存重复清单」，确认历史数据状态。

---

## 1. 代码事实基线（本次实测，不采信文档）

> 下表是 2026-10-09 计划编写时的实测值。**Flyway 版本 / 现有统计接口 / 权限码 三行已按当日收尾结果刷新**
> （V9 建 `inventory`+`inventory_log`、V10 删 `product_sku.stock`、项 1a 新增任务日志查询接口）；其余行未变。

| 事实 | 值 | 核实方式 |
|---|---|---|
| 模块数 | 9（common/user/admin/product/cart/order/pay/stats/web） | 父 `pom.xml` `<modules>` |
| Flyway | **V1~V10**，主库与 `migration-test` 双侧齐全（V9 建 `inventory`/`inventory_log`；V10 删 `product_sku.stock`） | `ls db/migration/` |
| `@Scheduled` 生效点 | **3 处**：`OrderTimeoutTask` 60s / `CartRecallTask` 每日 4:00 / `ExcelTaskCleanupTask` 每日 3:30 | grep |
| `product.name` 索引 | **无唯一索引**，只有 `idx_category_id` / `idx_status` | 读 `V2__init_product_schema.sql:23-40` |
| `product_sku.sku_code` | 有 `uk_sku_code`（全局唯一，不区分逻辑删除） | 同上 `:57` |
| `stats_task_log` | 表存在，含 `status`/`duration_ms`/`row_count`/`error_msg` | 读 `V7__cart_recall.sql:59-73` |
| `StatsTaskLogMapper` | **只被 `CartRecallTask` 的写路径使用，无任何查询接口** | grep 全仓 |
| 7000 段错误码 | 已用 `7001/7002/7003`（`7004+` 空闲） | 读 `ResultCode.java:78-81` |
| 现有统计接口 | `/api/admin/stats/recall/{summary,products,targets,build}` + `/api/admin/stats/task-logs`（项 1a 新增） | 读 `AdminRecallController` / `AdminTaskLogController` |
| 权限码 | `stats:recall:list` / `stats:recall:build` / `stats:task-log:list`（项 1a 新增） | `AdminDataInitializer.java:214-215` |

### 分支策略（用户 2026-10-09 决定）

**本仓库分支一律保留，永不删除**（本地与远端都不删），已合并的分支也长期留着。
本计划**不含分支清理项**，也不把「冗余分支」列为缺口或建议。

---

## 2. 事项明细

### 项 1：定时任务可观测补口

> ✅ **1a 已全部落地（2026-10-09）。** 交付物：`StatsTaskLogPageQuery` / `StatsTaskLogVO` /
> `TaskLogQueryService(+Impl)` / `AdminTaskLogController`（`GET /api/admin/stats/task-logs`），
> 权限码 `stats:task-log:list` 已在 `AdminDataInitializer` 幂等注册；
> 测试 `TaskLogQueryServiceImplTest`（8 用例）+ `AdminTaskLogQueryIntegrationTest`（7 用例）。
> 全量 **424 用例 BUILD SUCCESS**。
>
> **1b（失败告警）明确不做**：真正的告警需要一个外部 webhook 地址（企微/钉钉），
> 没有地址就只能做「log.warn」这种假告警 —— 那和现在的 `log.error` 没有本质区别。
> 下面 1b 的设计保留作留档，**不要按它开工**。
>
> **两处实现取舍**（值得记住）：
> 1. **日期按「执行时间」`start_time` 过滤，不按业务日期 `stat_date`**：运营问的是
>    「这几天任务跑没跑」，那是执行时刻的语义；而且表上只有
>    `idx_stats_task_log_name_time (task_name, start_time)`，按 `stat_date` 过滤用不上索引。
>    手动补数（为过去的 `stat_date` 重跑）也只在执行时间上看得见。
> 2. **日期用半开区间 `[startDate 00:00, endDate+1 00:00)`**，不写 `endDate 23:59:59`：
>    后者要猜列精度，且会漏掉当天最后一秒的记录 —— 而定时任务正好在凌晨触发，
>    运营查「昨天」时最容易踩到这个边界。已用集成测试钉住「次日 0 点整的记录不算进来」。

**要补的两个真实缺口**（都已核实）：

1. **`stats_task_log` 有写无读**：`CartRecallTask` 每天把执行结果写进表，但
   `StatsTaskLogMapper` **全仓只被它自己引用**，没有任何查询接口 ⇒ 运营无法回答
   「今天圈人任务跑没跑、产出多少行、有没有失败」。
2. **任务失败无告警**：失败只留一行 `log.error`，没人看日志就等于静默失败。
   （`OrderTimeoutTask` 与 `ExcelTaskCleanupTask` **连日志表都没有**。）

**范围建议**：只做 1a；1b 先设计不实现。

#### 1a（必做）：任务执行日志查询接口

**Files**：

| 操作 | 文件 |
|---|---|
| 新增 | `spring-shop-stats/src/main/java/com/springshop/stats/vo/StatsTaskLogVO.java` |
| 新增 | `spring-shop-stats/src/main/java/com/springshop/stats/service/TaskLogQueryService.java` |
| 新增 | `spring-shop-stats/src/main/java/com/springshop/stats/service/impl/TaskLogQueryServiceImpl.java` |
| 新增 | `spring-shop-stats/src/main/java/com/springshop/stats/controller/admin/AdminTaskLogController.java` |
| 修改 | `spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java`（加权限码 + 子菜单） |
| 新增 | `spring-shop-stats/src/test/java/com/springshop/stats/service/TaskLogQueryServiceImplTest.java` |
| 新增 | `spring-shop-web/src/test/java/com/springshop/web/AdminTaskLogQueryIntegrationTest.java` |

**设计要点（都是踩过坑的地方）**：

- **路径用 `/api/admin/stats/task-logs`，新建独立 Controller**，不要塞进 `AdminRecallController`
  —— 后者的 `@RequestMapping` 是 `/api/admin/stats/recall`，任务日志不属于 recall 子域，
  塞进去会让 URL 变成 `/recall/task-logs`，语义不对。
- **返回 VO 而不是 entity**：AGENTS 红线「Entity 不直接返回给前端」。
  注意现有 `products` / `targets` 接口直接返回了 entity，属**既有违规**，本项**不顺手改**（避免扩大改动面），
  但新接口必须遵守。
- **分页用 `PageQuery`（`current` / `size`）**：上一轮 B1 刚加了 `PageParamGuardInterceptor`，
  它会**拒绝** `pageNo` / `pageSize` 这类错误参数名。新接口只要绑定 `PageQuery` 子类就自动受保护，
  是好事；但**测试里必须用正确的参数名**，否则会拿到 400 而不是分页结果。
- **查询条件**：`taskName`（默认 `cart-recall`）、`status`（1 成功 / 0 失败）、
  `startDate` / `endDate`、`PageQuery`。排序 `start_time DESC, id DESC`。
- **权限码**：新增 `stats:task-log:list`，在 `AdminDataInitializer` 里仿照
  `:214-215` 的 `ensureMenu(...)` 加一个子菜单。**必须用 find-or-create 的既有写法**，保证幂等
  （该初始化器已支持升级路径，见 `2026-10-03.md` 的相关测试）。
- **`SecurityConfig` 无需改动**：`/api/admin/**` 已走后台过滤链。
- **错误码**：本项为只读查询，**预计不需要新错误码**；若确实要加，用 7000 段的 `7004+`。
- **测试四件套不能省**：集成测试必须 `@SpringBootTest` + `@AutoConfigureMockMvc` +
  `@ActiveProfiles("test")` + `@Transactional` + `@MockBean StringRedisTemplate`（缺一 CI 必挂）。

**验收**：`mvn test` 全绿（当前基线 324 用例，本项预计 +6~8）；
手工 `POST /api/admin/stats/recall/build` 后再 `GET /api/admin/stats/task-logs` 能看到该次执行记录。

#### 1b（可选，建议先不实现）：失败告警

**为什么建议先不做**：真正的告警需要一个**外部 webhook 地址**（企微/钉钉），
属外部依赖，且没有地址就只能做「log.warn」这种假告警 —— 那和现在的 `log.error` 没有本质区别。

**若要做**，建议形态（等 §3 决策表 D1 拍板）：

- 在 `spring-shop-stats` 加 `TaskFailureNotifier` 接口 + 默认 `LoggingTaskFailureNotifier` 实现，
  由 `CartRecallTask` 在写 `status=0` 日志后调用。
- **放 stats 而不是 common**：common 禁止业务逻辑；等定时任务平台化（`spring-shop-task`）落地时
  再上移为通用能力，避免现在就把抽象层摊开。
- 配置化：`stats.cart-recall.alert.enabled=false` 默认关，webhook 地址走环境变量。

---

### 项 2：商品唯一性规则落地（(名称, 规格) 未删除范围内唯一 + 仅下架可删）

> ✅ **本项已全部落地（2026-10-09）。** 全量 `mvn test` = **409 用例 BUILD SUCCESS**
> （product 73 → 95，web 130 → 143）。落地时的两处**实现取舍**记录如下，供以后回溯：
> 1. **查重 SQL 用 LEFT JOIN**（`ProductMapper.selectOccupiedProductSpecs`）：库里存在
>    「SKU 全被逻辑删除、商品本身还在」的行，INNER JOIN 会让它在结果里消失 ⇒ 导入会给它
>    再建一个同名 SPU。LEFT JOIN 让这类商品以 `specs = null` 出现，消费方**必须跳过 null**
>    （既拿到 `productId` 复用，又不把「没有 SKU」当成「占用了一个空规格」）。
> 2. **导入复用 SPU 时，商品级字段（分类 / 副标题 / 主图）一律忽略**：这三列描述 SPU，
>    而本次只追加 SKU。所以复用分支既不解析分类、也不写这三个字段。
>    ⚠️ 这是「只追加 SKU，不新建 SPU」的字面落地，**若期望「导入顺带改已有商品的分类/副标题」，
>    需要另行确认并改这里**。
>
> 另：原文 2c 里「修改商品是『先删旧 SKU 再重建』」已随 2f 的 upsert 改造失效，
> 按商品粒度排除自己的做法仍然保留（更稳，不依赖 SKU id 稳定）。

> **规则已于 2026-10-09 由用户定稿**，后续**所有**唯一性判断都按此执行。
> 完整规则已同步进项目 `MEMORY.md`「🔴 商品唯一性口径」一节。

**规则**（前提：**单店模式**，唯一性范围全局）：

| 项 | 规则 |
|---|---|
| 唯一性单元 | **(商品名称, 规格)** —— SKU 粒度，**不是** `sku_code` |
| 判定范围 | **仅未删除记录**（`is_deleted = 0`） |
| 同名 + 不同规格 | **合法**，是**同一个 SPU 下的两个 SKU** |
| 同名 + 同规格 | 视为同一 SKU，**未删除范围内不允许两条** |
| `sku_code` | **保留全局唯一**（`uk_sku_code` 不动），两套口径并存 |
| 删除后重新新增同名同规格 | **新增一条新记录（新 id）**，不复用旧记录 |
| 删除前置条件 | **仅下架(status = 0)商品可删**；删除是**逻辑删除** |

**现状缺口**（2026-10-09 读码核实，四处）：

1. **后台新增/修改对商品名零校验**：`ProductManageServiceImpl` 只有 `validateSkuCodesUnique()`
   （校验 `sku_code`），`buildProduct()` 直接写入 `request.getName()` ⇒ 无需并发即可造重名。
2. ⚠️ **导入语义与新规则直接冲突**：现状是「按**名称**整组拒绝」
   （`loadOccupiedProductNames` → `buildGroup`），而新规则要求**同名不同规格必须放行**。
   ⇒ 这是本次**改动最大**的一处。
3. **商品无删除接口**：`AdminProductController` 无 `DeleteMapping`（分类有，商品没有）。
4. `product_sku.specs` 是 `VARCHAR(255) DEFAULT NULL`，**可空**；且是**自由文本**
   ⇒ 不规范化则「唯一」会被绕过（见下方规范化规则）。

**为什么不做库级唯一索引（本次已拍板：先只做应用层查重）**：
唯一性键跨 `product` 与 `product_sku` **两张表**，单条唯一索引无法表达；
要落库必须把 `name` 冗余进 `product_sku`（改名要同步刷 SKU）、把 `specs` 改 `NOT NULL DEFAULT ''`，
还要验证 H2 是否支持生成列 DDL。**本轮不做**，留作后续强化。

#### 2a 规格字符串规范化（口径的一部分，必须先做）

`specs` 是自由文本（如 `颜色:黑;尺寸:L`）。直接比原文则 `颜色:黑;尺寸:L` 与
`尺寸:L;颜色:黑` 会被当成两个商品，规则形同虚设。规范化步骤：

1. `trim`；
2. 分隔符统一（`；` / `,` / `、` → `;`）；
3. 每段 `trim`，去掉空段；
4. **按段排序**（顺序不同视为同一规格）；
5. `null` / 空串 → 统一空串（**不能留 null**，否则比较失效）。

#### 2b 统一查重能力

新增一个查重方法（`ProductMapper`），一次查回「名称 → 已占用的规格集合」：

```sql
SELECT p.id AS productId, p.name AS name, s.specs AS specs
FROM product p
JOIN product_sku s ON s.product_id = p.id
WHERE p.is_deleted = 0 AND s.is_deleted = 0
  AND p.name IN (...)
```

- 应用层用**规范化后的 `(name, specs)` 键**比对。
- ⚠️ `@Select` 注解 SQL **不走 MP 逻辑删除插件**，`is_deleted = 0` 必须**显式写**
  （现有 `selectOccupiedProductNames` 注释已写明这一点）。
- 导入场景需要**分批查**（现有 `QUERY_CHUNK` 的模式），不能一行一查。

#### 2c 三处调用点

| 调用点 | 改动 |
|---|---|
| `POST /api/admin/products`（新增） | 校验提交的每个 SKU 的 (name, specs) 是否与未删除记录冲突；**同时校验本次提交内部 specs 不重复**（现有只查 skuCode） |
| `PUT /api/admin/products/{id}`（修改） | 同上，但**必须排除本商品自己**。注意现有 update 是「先删旧 SKU 再重建」（`saveSkus(..., isCreate=false)`），所以按**商品粒度排除**（排除本商品的全部 SKU）比按 SKU id 排除更稳 |
| 导入 `ProductImportServiceImpl` | **语义变更**：从「按名称整组拒绝」改为**按 (名称, 规格) 逐行判定**。同名不同规格 → **复用现有 `productId`，只追加 SKU，不新建 SPU**；同名同规格 → 拒绝该行。`persistGroups` 现在对每组都 `insertBatch(products)` 新建 SPU，要改成「名称已存在则跳过建 SPU」 |

#### 2d 新增删除接口（仅下架可删）

`DELETE /api/admin/products/{id}`：

1. 校验商品存在（`PRODUCT_NOT_FOUND` 2010）；
2. **校验 `status = 0`（下架）**，否则抛新错误码 `PRODUCT_NOT_OFF_SHELF(2016)`；
3. 逻辑删除商品，**并连带逻辑删除其全部 SKU 与图片**
   （否则残留 SKU 的 `sku_code` 仍占着全局唯一编码，语义不干净）；
4. 权限码新增 `product:product:delete`，在 `AdminDataInitializer` 里幂等注册菜单；
5. 失效商品详情缓存 `RedisKeys.productDetail(id)`（与现有 updateStatus 一致）。

#### 2e 错误码（2000 段，已确认空闲）

| 码 | 名称 | 用途 |
|---|---|---|
| 2015 | `PRODUCT_IDENTITY_DUPLICATE` | 同名同规格的商品已存在 |
| 2016 | `PRODUCT_NOT_OFF_SHELF` | 商品未下架，不可删除 |

#### 2f ⚠️ 一并修三个现存 bug（与唯一性规则**同源**，2026-10-09 读码发现）

**同一根因**：`saveSkus()` 在**修改**商品时用「**删掉全部 SKU 再重新插入**」
（`isCreate=false` 时先 `productSkuMapper.delete(...)` 再逐条 `insert`）。

| Bug | 机制 | 为什么一直没被发现 |
|---|---|---|
| **A. 修改商品必然 500** | `delete` 是**逻辑删除**（`ProductSku` 有 `@TableLogic`）⇒ 旧行还在、`sku_code` 还占着；随后 insert 同样的 `sku_code` ⇒ 撞 `uk_sku_code`（**不区分 `is_deleted`**）⇒ `DuplicateKeyException` | 单测 `update_should_evict_detail_cache` 是**纯 Mockito**（mapper 被 mock，不碰库）；**集成测试里没有任何用例打 `PUT /api/admin/products/{id}`** |
| **B. 修改商品把库存静默清零** | `ProductSkuItem.stock` **没有 `@NotNull`**，写入用 `Objects.requireNonNullElse(item.getStock(), 0)` ⇒ 前端不传 stock 就变 0 | 同上 |
| **C. 修改商品换掉所有 SKU 的 id** | 「删旧插新」⇒ 新 SKU 拿到**新自增 id**，而 `cart_item.sku_id` / `order_item.sku_id` 仍指向旧 SKU ⇒ 购物车里该商品的型号信息查不到 | 同上 |

> 又是「**纯 Mockito 单测 + 无集成覆盖 ⇒ 结构性不可见**」的同一个盲区
> （与项目记忆里「缓存一致性」那节的教训完全同型）。

**修法（与 2c 的唯一性改造合并做）**：把 `saveSkus` 从「删旧插新」改成
**按规格 upsert、保留 `sku_id`**：

1. 查出该商品现有**未删除**的 SKU，按**规范化规格**建索引；
2. 遍历请求里的 SKU：能匹配到 → `updateById`（**保留 id，Bug A/C 随之消失**）；匹配不到 → `insert`；
3. 请求里没有、库里有的 SKU → 逻辑删除（保持「请求 = 全量」的既有前端契约）；
4. **Bug B 的修法要区分新增/修改**：**新增**时 stock 为 null → 视为 0；
   **修改**时 stock 为 null → **保持原值**，不能置 0。

⚠️ **新增待确认项**：第 3 步删除 SKU 前，是否要检查该 SKU 是否被 `cart_item` 引用？
直接逻辑删除会让购物车条目变成脏引用（型号信息查不到）。
建议：**有购物车引用则拒绝删除并提示**，但这属于产品决策 —— 见 §3 决策点 D3。

#### Files

| 操作 | 文件 |
|---|---|
| 修改 | `spring-shop-common/.../result/ResultCode.java`（+2015 / 2016） |
| 修改 | `spring-shop-product/.../product/mapper/ProductMapper.java`（+ 按名称批量查 (productId, specs)） |
| 新增 | `spring-shop-product/.../product/support/SkuSpecNormalizer.java`（规格规范化，纯函数好测） |
| 修改 | `spring-shop-product/.../product/service/impl/ProductManageServiceImpl.java`（新增/修改查重 + `saveSkus` 改按规格 upsert（修 Bug A/B/C）+ 新增 `delete`） |
| 修改 | `spring-shop-product/.../product/service/ProductManageService.java`（+ `void delete(Long id)`） |
| 修改 | `spring-shop-product/.../controller/admin/AdminProductController.java`（+ `DeleteMapping("/{id}")`） |
| 修改 | `spring-shop-product/.../product/service/impl/ProductImportServiceImpl.java`（按 (名称,规格) 判定 + 复用已有 SPU 追加 SKU） |
| 修改 | `spring-shop-admin/.../config/AdminDataInitializer.java`（+ `product:product:delete` 权限） |
| 新增 | `spring-shop-product/src/test/java/.../SkuSpecNormalizerTest.java` |
| 修改 | `spring-shop-product/src/test/java/.../ProductManageServiceImplTest.java`（查重 + 删除状态校验） |
| 修改 | `spring-shop-web/src/test/java/.../AdminProductIntegrationTest.java`（新增/修改/删除/导入 四条链路） |
| **新增** | `spring-shop-web/src/test/java/.../ProductUpdateIntegrationTest.java`（**专门覆盖 `PUT /api/admin/products/{id}`** —— 这是 Bug A/B/C 的回归防线，此前完全空缺） |

**验收**：

- `mvn test` 全绿（基线 324，本项预计 +12~18）。
- 手工验证：新增「夏日T恤 / 颜色:黑」成功 → 再新增「夏日T恤 / 颜色:黑」被拒（2015）
  → 新增「夏日T恤 / 颜色:白」**成功**（同一 SPU 两个 SKU）
  → 规格写成「颜色：黑」（全角冒号）也能被识别为重复
  → 上架状态删除被拒（2016）→ 下架后删除成功且商品从列表消失。

**风险**：

- 导入语义变更影响面最大，**必须回归现有导入测试**（尤其「同名整组拒绝」的既有用例要按新语义改写）。
- 规范化排序后，历史数据里顺序不同的规格会被视为重复 —— 上线前需要一份
  「现存重复 (名称, 规格)」清单（见下方 SQL），否则新校验会让老数据「无法再新增同规格」。

**前置：现存重复清单**（上线前跑一次，确认历史数据是否已经有重复）。
需连生产库执行 —— 需要你提供库访问方式或自行执行：

```sql
-- ① 未删除商品里，「同名 + 同规格」的重复
SELECT p.name, s.specs, COUNT(*) AS cnt, GROUP_CONCAT(s.id) AS sku_ids
FROM product p
JOIN product_sku s ON s.product_id = p.id
WHERE p.is_deleted = 0 AND s.is_deleted = 0
GROUP BY p.name, s.specs
HAVING COUNT(*) > 1;

-- ② 同一 SPU 内重复规格（本次提交内部去重也要拦这类）
SELECT s.product_id, s.specs, COUNT(*) AS cnt, GROUP_CONCAT(s.id) AS sku_ids
FROM product_sku s
WHERE s.is_deleted = 0
GROUP BY s.product_id, s.specs
HAVING COUNT(*) > 1;
```

> 注：上面按**原文**分组。若历史数据里存在「同规格不同顺序 / 全角半角差异」，
> 规范化后才会暴露成重复 —— 建议再加一版按规范化结果分组的人工核对。

---

### 项 3：库存流水表 `stock_log`（单仓）

> 🔴 **本项已作废并已被取代，不要按下面执行。**（2026-10-09 16:00 标注）
>
> 用户随后要求「向参考模型对齐」，方案从「在 `product_sku` 上加 `locked_stock` 列 +
> `stock_log` 流水表」**改为 `inventory` 独立表 + `inventory_log` 流水表**（Flyway **V9**），
> 并已分三阶段落地完成：① V9 + 6 实体；② 6 张表的 Mapper/Service/VO/Controller；
> ③ 业务调用点切换（下单 lock / 支付 outbound / 取消 release）。
> 全量测试 **373 用例 BUILD SUCCESS**。
>
> ⇒ 库存相关**当前真相**见 `.workbuddy-ai/memory/MEMORY.md` 的
> 「🔴 数据模型对齐 V9」与「✅ 第 3 阶段已完成」两节。
> 下面保留原文仅作决策演进留档；其中「没有独立库存表」「写路径只有三条」的描述**已过时**。

**背景（2026-10-09 核实）**：**项目里没有独立库存表**。全仓 21 张表里 `stock` **只出现一次**
—— `product_sku.stock INT NOT NULL DEFAULT 0`（`V2:50`）。
即库存是**挂在 SKU 上的一个列**，粒度正好是「商品型号」
⇒ **「随时查每个型号的库存」现在就能查**（按 `product_id` 查该 SPU 下所有 SKU），不需要新表。

库存的**全部写路径只有三条**：

| 路径 | SQL | 调用点 |
|---|---|---|
| 下单扣减 | `SET stock = stock - ? WHERE id = ? AND stock >= ?`（条件更新，0 行 = 库存不足） | `OrderServiceImpl:111` |
| 取消回滚 | `SET stock = stock + ? WHERE id = ?` | `OrderServiceImpl:316-318` |
| 后台覆盖 | `sku.setStock(requireNonNullElse(item.getStock(), 0))` | `ProductManageServiceImpl:161`（⚠️ Bug B） |

**缺的两个能力**：

1. **没有变更流水** ⇒ 库存对不上账时**无法追溯**。
2. **没有锁定库存** ⇒ 下单**立即真实扣减**（未支付也扣），超时取消再加回
   ⇒ `stock` 里**混着「未付款订单占用的量」**，运营看到 0 会以为真卖完了。

**决策（2026-10-09 用户拍板）**：**单仓**（不需要 warehouse / sku_stock 表）；
**新增 `stock_log`**；**本轮不做锁定库存**（拆 `stock` / `locked_stock` 会波及订单、支付、
超时取消三处，留作后续）。

#### 表设计

```sql
CREATE TABLE IF NOT EXISTS `stock_log` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `sku_id`       BIGINT       NOT NULL COMMENT 'SKU id',
    `product_id`   BIGINT       NOT NULL COMMENT '商品 id（冗余，便于按商品维度查）',
    `change_type`  TINYINT      NOT NULL COMMENT '1 下单扣减 / 2 取消回滚 / 3 后台调整 / 4 导入初始化',
    `change_qty`   INT          NOT NULL COMMENT '变更量：正数增加、负数减少',
    `before_stock` INT          NOT NULL COMMENT '变更前库存',
    `after_stock`  INT          NOT NULL COMMENT '变更后库存',
    `biz_no`       VARCHAR(64)  DEFAULT NULL COMMENT '关联业务单号（订单号等）',
    `operator_id`  BIGINT       DEFAULT NULL COMMENT '操作人（后台调整时为管理员 id）',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_stock_log_sku_time` (`sku_id`, `create_time`),
    KEY `idx_stock_log_biz_no` (`biz_no`),
    KEY `idx_stock_log_product` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存变更流水';
```

**设计取舍（都要写进代码注释）**：

- **append-only**：只插入，**不更新不删除** ⇒ 因此**没有** `is_deleted` / `version`
  —— 与项目其他表**刻意不同**，这是流水表的本质（它是「账」，不是「状态」）。
- **同时存 `before_stock` 与 `after_stock`**：只存 `change_qty` 的话，中间错一条后面全错、
  无法定位；存了前后值就能逐条核对（上一条的 `after` 应等于下一条的 `before`）。
- **冗余 `product_id`**：按商品维度查流水不必 join `product_sku`。
- **`biz_no` 存订单号**：这是「按订单追溯库存变化」的钥匙。

#### 四类写入点

| change_type | 触发 | 调用点 |
|---|---|---|
| 1 下单扣减 | 下单成功扣库存 | `OrderServiceImpl.create()` |
| 2 取消回滚 | 用户取消 / 超时自动取消 | `rollbackStockAndSales` |
| 3 后台调整 | 商品保存时改了库存（项 2 的 upsert） | `ProductManageServiceImpl` |
| 4 导入初始化 | 批量导入新建 SKU | `ProductImportServiceImpl` |

#### ⚠️ 关键实现细节（容易算错，必须注意）

- **MySQL 的 `UPDATE` 不返回旧值** ⇒ 正确做法是在**同一事务内**：
  先 `deductStock`（条件更新，0 行即库存不足）→ 成功后 `selectById` 拿 `after_stock`
  → `before_stock = after_stock + quantity`。
- **必须与扣减在同一事务**（`create()` 已是 `@Transactional`）：刚更新过该行、本事务持有行锁，
  读到的值才准确；跨事务读会被别人改过。
- **流水与扣减同事务提交** ⇒ 保证「扣了库存必有账」。宁可一起回滚，也不要出现「扣了但没账」。

#### 查询接口（后台）

`GET /api/admin/products/skus/{skuId}/stock-logs`（分页，用 `PageQuery`，
注意上一轮 B1 加的 `PageParamGuardInterceptor` 会拒绝错误参数名）；
权限码 `product:stock:log`，在 `AdminDataInitializer` 里幂等注册。

#### Files

| 操作 | 文件 |
|---|---|
| **新增** | `spring-shop-web/src/main/resources/db/migration/V9__stock_log.sql` |
| **新增** | `spring-shop-web/src/test/resources/db/migration-test/V9__stock_log.sql`（**副本，索引名全局唯一**） |
| 新增 | `spring-shop-product/.../product/entity/StockLog.java` |
| 新增 | `spring-shop-product/.../product/mapper/StockLogMapper.java` |
| 新增 | `spring-shop-product/.../product/service/StockLogService.java` + `impl/StockLogServiceImpl.java` |
| 新增 | `spring-shop-product/.../product/vo/StockLogVO.java` + `dto/StockLogQuery.java` |
| 修改 | `spring-shop-product/.../controller/admin/AdminProductController.java`（+ 流水查询端点） |
| 修改 | `spring-shop-order/.../service/impl/OrderServiceImpl.java`（扣减/回滚后写流水） |
| 修改 | `spring-shop-product/.../service/impl/ProductManageServiceImpl.java`（后台调库存写流水） |
| 修改 | `spring-shop-product/.../service/impl/ProductImportServiceImpl.java`（导入初始化写流水） |
| 修改 | `spring-shop-admin/.../config/AdminDataInitializer.java`（+ `product:stock:log` 权限） |
| 新增 | `spring-shop-product/src/test/java/.../StockLogServiceImplTest.java` |
| 新增 | `spring-shop-web/src/test/java/.../StockLogIntegrationTest.java` |

⚠️ **`spring-shop-order` 需要能写库存流水**：流水实体与 Service 放在 `spring-shop-product`
（库存归属商品域），order 已依赖 product，**依赖方向不变**（`web → order → product → common`）。

**验收**：

- `mvn test` 全绿（基线 324，本项预计 +10~15）。
- 手工验证：下单后 `stock_log` 出现一条 type=1、`before/after` 正确；
  取消后出现 type=2 且数值对称；后台改库存出现 type=3 且带 `operator_id`。
- **对账校验**：`SELECT sku_id, SUM(change_qty) FROM stock_log GROUP BY sku_id`
  的结果应与 `product_sku.stock` 一致（**这是流水表存在的意义，必须验证**）。

---

## 3. 决策点（待你拍板）

| # | 决策点 | 选项 | 我的建议 |
|---|---|---|---|
| **D1** | 项 1 是否含失败告警（1b） | 含（需 webhook 地址）/ 不含 | **不含**，先只做查询接口 |
| **D2** | 项 2 是否现在开工 | 现在做 / 只留设计 | 项 2 的**规则与方案已全部定稿**（2026-10-09），只差开工指令 |
| **D3** | 项 2 修改商品时，某 SKU 被 `cart_item` 引用，是否允许逻辑删除 | 拒绝删除并提示 / 允许删除（购物车条目变脏引用） | **拒绝删除并提示**（见下方说明） |
| **D4** | 项 3 是否现在开工 | 现在做 / 只留设计 | 项 3 设计与 DDL 已定稿，但**依赖项 2 的 `saveSkus` 改造**，建议排在项 2 之后 |

> **D3 说明**：项 2 的 `saveSkus` 改成「请求 = 全量」的 upsert 后，请求里没带的 SKU 会被逻辑删除。
> 但 `cart_item.sku_id` 仍指向它 —— 该购物车条目此后查不到型号信息（成为脏引用）。
> 建议做法：删除前查 `cart_item` 是否有未删除引用，有则**拒绝该次保存**并提示
> 「规格 X 已被 N 个购物车引用，无法删除」，由运营先去处理。
> **代价**：需要 `spring-shop-product` 能读 `cart_item`（product **不依赖 cart**，
> 反向依赖会破坏模块方向）⇒ 只能用 `@Select` 裸 SQL 或加一个跨模块只读接口。
> 这是**唯一会破坏依赖方向**的地方，若你不想破坏方向，则 D3 改选「允许删除」。

> 项 2 原来的五个待定项**已全部闭环**（用户 2026-10-09 拍板）：
> ① 未删除范围内唯一（不允许重复）；② 同名不同规格 = 同一 SPU 下两个 SKU；
> ③ `sku_code` 保留全局唯一；④ 先只做应用层查重（不做库级唯一索引）；
> ⑤ 新增删除接口，仅下架可删。

---

## 4. 明确不在本轮范围（留档，别当待办）

| 项 | 原因 |
|---|---|
| **环境统一（`.mvn/wrapper` + `.java-version`）** | **已否决，勿重启**（用户 2026-10-09 13:42 明确「不做，去掉这个任务」） |
| 定时任务平台化 `spring-shop-task` | 大件，7 项决策点未定；用户 2026-10-09 明确「大件不需要」 |
| 券体系 + 触达（召回 Phase 2/3） | 大件 + 资损高风险（改 `OrderServiceImpl.create()` 金额链路） |
| `cart_add_log` 加购流水埋点 | 同上，属 Phase 2 |
| Docker Compose 一键交付 | 本机无 Docker / colima / OrbStack，外部阻塞 |
| Excel 剩余盲区（A5/A6/A8/A9/A10~A16） | 上一轮未选 |
| 支付回调（真实第三方） | 需外部账号与资质 |
| 接口防刷限流 | 低优先级，上线前再说 |
| REST→MCP | **已否决，勿重启** |

---

## 5. 执行顺序与验收（若全部批准）

```
项 2 商品唯一性   → mvn test 全绿（324 → 409，实际 +35）→ ✅ 已落地（商品域收口）
项 3 库存流水     → 由 V9/V10 取代（stock_log → inventory_log）→ ⚠️ 已作废，勿执行
项 1a 日志查询    → mvn test 全绿（409 → 424，实际 +15）→ ✅ 已落地
```

> **顺序理由**：项 3 的「后台调整库存写流水」落在项 2 改造后的 `saveSkus` 上，
> 先做项 2 可避免项 3 返工；项 1a 与其余两项完全无耦合，放最后。

> ⚠️ **提交状态（2026-10-09 收尾核对）**：`main` = `origin/main` = `ad1cbdb`。
> 本轮的**数据模型对齐（V9 三阶段）+ 商品域收口 + V10 + 项 1a 全部还在工作区，尚未提交**
> （约 87 个改动/新增文件）。**未执行任何 git 写操作**，是否提交待用户决定。

**每项完成后按第一原则汇报**：改了什么、动了哪些文件、有无副作用路径、如何回滚。

**红线复核**（沿用 AGENTS.md）：

- 不改历史 Flyway 脚本；**项 2 不新增迁移**（走应用层查重，不碰库表）；
  **项 3 新增 V9**，必须**主库与 `migration-test` 双侧同步**，且**索引名全局唯一**
  （H2 是库级唯一）——这是 CI 红线。
- 新增接口必须返回 `Result<T>` 且用 VO，不暴露 entity。
- 新增权限码必须在 `AdminDataInitializer` 里幂等注册，否则「忘了配权限 → 永久 403」。
- 集成测试四件套缺一 CI 必挂。
- 项 2 改动导入语义后，**必须同步更新 AGENTS.md 里关于导入「按名称整组拒绝」的描述**
  （该描述已过时）。
- 项 3 的库存流水**必须与扣减在同一事务提交**，且用「同一事务内更新后回读」拿 `after_stock`。
