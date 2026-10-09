# MEMORY.md — spring-shop 项目长期记忆

> 只记「跨会话仍然成立」的项目约定与判断，不记流水账（流水账在 `YYYY-MM-DD.md`）。

## 🔴 商品唯一性口径（用户 2026-10-09 定稿，**后续所有唯一性判断都按此规则**）

**前提：单店模式**（不分店铺，唯一性范围是全局的）。

### 规则

| 项 | 规则 |
|---|---|
| **唯一性单元** | **(商品名称, 规格)** —— 即 **SKU 粒度**，不是 `sku_code` |
| **判定范围** | **仅未删除的记录**（`is_deleted = 0`）。已删除的不参与判定 |
| **同名 + 不同规格** | **合法的**，是**同一个 SPU 下的两个 SKU**（沿用现有 SPU/SKU 模型） |
| **同名 + 同规格** | 视为同一个 SKU，**未删除范围内不允许存在两条** |
| **`sku_code`** | **保留全局唯一**（`uk_sku_code` 不动）。它与上面这套口径**并存**，互不干扰 |
| **删除后重新新增同名同规格** | **直接新增一条新记录（新 id）**，不复用旧记录 |

### 商品状态与删除

- 商品有上架(1) / 下架(0) 状态。
- **只有下架(status = 0)的商品才允许删除**；上架商品删除必须拒绝。
- **删除是逻辑删除**（`is_deleted = 1`），行保留在表里。
- 建议连带逻辑删除该商品的所有 SKU 与图片（否则残留 SKU 的 `sku_code` 仍占着全局唯一编码）。

### ⚠️ 规格字符串必须规范化（否则「唯一」会被绕过）

`product_sku.specs` 是**自由文本**（如 `颜色:黑;尺寸:L`）。如果直接拿原文比对，
`颜色:黑;尺寸:L` 与 `尺寸:L;颜色:黑` 会被当成两个不同商品 —— **规则形同虚设**。
规范化至少要做：trim → 统一分隔符 → 每段 trim → **按段排序** → 空/null 归一为空串。
（`specs` 在库里是 `VARCHAR(255) DEFAULT NULL`，**可空**；应用层必须把空值归一成同一个键。）

### ✅ 本项已全部落地（2026-10-09 17:20，全量 409 用例 BUILD SUCCESS）

| 原缺口 | 现状 |
|---|---|
| 后台新增/修改对商品名零校验 | ✅ 加 `validateIdentitiesUnique`（2015）。校验顺序：`SKU空 → sku_code → (名称,规格) → 分类`（唯一性**必须**排在分类之前，否则把运营引到无关错处） |
| 导入「按名称整组拒绝」与新规则冲突 | ✅ 改为**按 `(名称, 规格)` 逐行判定**；同名不同规格**复用现有 `productId` 只追加 SKU**，同名同规格只拒那一行 |
| 商品无删除接口 | ✅ 加 `DELETE /api/admin/products/{id}`（仅下架可删，否则 2016）+ 权限 `product:product:delete`（已幂等注册菜单） |
| `@Select` 不走 MP 逻辑删除插件 | 依旧成立：`is_deleted = 0` 必须显式写 |

**落地时的两个取舍（可能需要回看）**：

1. 查重 SQL `ProductMapper.selectOccupiedProductSpecs` 用 **LEFT JOIN**，
   且 `s.is_deleted = 0` 必须写在 **`ON`** 里（写进 `WHERE` 会退化成 INNER JOIN）。
   否则「SKU 全被逻辑删除、商品本身还在」的同名商品会从结果里消失
   ⇒ 导入给它**再建一个同名 SPU**（正是规则要消灭的现象）。
   代价：消费方**必须跳过 `specs == null`** 的行（那不是「占用了一个空规格」）。
2. 导入**复用 SPU 时商品级字段（分类 / 副标题 / 主图）一律忽略** —— 本次只追加 SKU，不改 SPU。
   若期望「导入顺带改已有商品的分类/副标题」，需回来改这里。

**副作用**：`SaveRequest.sku.stock` 现在被解读为**绝对值**（原来是「新插入行的初始值」）；
修改时经 `InventoryService.adjust(...)` 同步到 `inventory`（**不直接改表**，否则「改了库存必有账」失效），
值没变则跳过以免留无意义流水。

**回归防线**：新建 `spring-shop-web/.../ProductUpdateIntegrationTest`（12 用例）——
**此前没有任何用例打过 `PUT /api/admin/products/{id}`**，是 Bug A/B/C 的结构性盲区所在。

### ✅ 三个 bug 已全部修复（2026-10-09，随项 2 一起做）

**同一个根因**：`ProductManageServiceImpl.saveSkus()` 在**修改**商品时用「**删掉全部 SKU 再重新插入**」
（`isCreate=false` 时先 `productSkuMapper.delete(...)` 再逐条 `insert`）。下表留档，便于理解为什么当时没被发现。

| Bug | 机制 | 为什么没被发现 |
|---|---|---|
| **A. 修改商品必然 500** | `delete` 是**逻辑删除**（`ProductSku` 有 `@TableLogic`）⇒ 旧行还在、`sku_code` 还占着；随后 insert 同样的 `sku_code` ⇒ 撞 `uk_sku_code`（**不区分 `is_deleted`**）⇒ `DuplicateKeyException` | 单测 `update_should_evict_detail_cache` 是**纯 Mockito**（mapper 被 mock，不碰库）；**集成测试里没有任何用例打 `PUT /api/admin/products/{id}`** |
| **B. 修改商品把库存静默清零** | `ProductSkuItem.stock` **没有 `@NotNull`**，而写入用 `Objects.requireNonNullElse(item.getStock(), 0)` ⇒ 前端不传 stock 就变 0 | 同上 |
| **C. 修改商品换掉所有 SKU 的 id** | 「删旧插新」⇒ 新 SKU 拿到**新自增 id**，而 `cart_item.sku_id` / `order_item.sku_id` 仍指向旧 SKU ⇒ 购物车里该商品的型号信息查不到 | 同上 |

> 这又是「**纯 Mockito 单测 + 无集成覆盖 ⇒ 结构性不可见**」的同一个盲区
> （与「缓存一致性」那节的教训完全同型）。
> **实际修法（已完成）**：`saveSkus` 从「删旧插新」改成「**按规范化规格 upsert、保留 `sku_id`**」
> —— 匹配到就 `updateById`、匹配不到才 `insert`、请求里没有的才逻辑删除。
> 这个行为**正好就是唯一性规则所要求的**（同名不同规格 = 同一 SPU 下的两个 SKU，
> 必须在已有 SKU 上做增量，不能整体重建）。⇒ 两件事一起做。
> ⚠️ Bug B 的修法区分了新增/修改：**新增**时 stock 为 null 视为 0；
> **修改**时 stock 为 null **保持原值**，不置 0。
>
> ⚠️ **V9 之后 Bug B 的破坏面变了**（修之前）：`saveSkus` 除写 `product_sku.stock`，
> 还会用同一个值调 `inventoryService.initStock(sku.getId(), ...)`
> ⇒ 前端不传 stock 时，**新建的 inventory 行在库量 = 0**，商品直接变成不可售
> （不只是老列被清零）。**修 Bug B 时必须同时处理 `inventory`** —— 已由
> `ProductUpdateIntegrationTest#update_withoutStock_shouldKeepInventoryStock` 钉住。

### 错误码（2000 段，2015/2016 已确认空闲）

- `PRODUCT_IDENTITY_DUPLICATE(2015, "同名同规格的商品已存在")`
- `PRODUCT_NOT_OFF_SHELF(2016, "商品未下架，不可删除")`

> 完整设计见 `docs/superpowers/plans/2026-10-09-next-round-hygiene-plan.md` 项 2。

## 库存模型（用户 2026-10-09 定稿：**单仓 + 加库存流水表**）

> ⚠️⚠️ **本节已被 V9「数据模型对齐」取代，仅作决策演进留档。**
> 「在 `product_sku` 上加 `locked_stock` 列」的方案**已作废**；实际落地是 `inventory` 独立表。
> 下面「现状：没有独立库存表」「写路径只有三条」的描述**已过时**（当前真相见
> 「🔴 数据模型对齐 V9」与「✅ 第 3 阶段已完成」两节）。**不要按本节写代码。**

### 现状：**没有独立库存表**

全仓 21 张表里 `stock` **只出现一次** —— `product_sku.stock INT NOT NULL DEFAULT 0`（`V2:50`）。
即**库存是挂在 SKU 上的一个列**，粒度正好是「商品型号」。
⇒ 「随时查每个型号的库存」**现在就能查**（按 `product_id` 查该 SPU 下所有 SKU），不需要新表。

库存的**全部写路径只有三条**（都已核实）：

| 路径 | SQL | 调用点 |
|---|---|---|
| 下单扣减 | `UPDATE product_sku SET stock = stock - ? , version = version + 1 WHERE id = ? AND stock >= ?`（条件更新，0 行 = 库存不足） | `OrderServiceImpl:111` |
| 取消回滚 | `UPDATE product_sku SET stock = stock + ? , version = version + 1 WHERE id = ?` | `OrderServiceImpl:316-318`（`rollbackStockAndSales`） |
| 后台覆盖 | `sku.setStock(requireNonNullElse(item.getStock(), 0))` | `ProductManageServiceImpl:161`（⚠️ 见上方 Bug B） |

### 两个能力缺口

1. **没有库存变更流水** ⇒ 库存对不上账时**无法追溯**（谁在什么时候把 100 改成 50、下单扣了多少、回滚了几次）。
2. **没有锁定库存** ⇒ 下单**立即真实扣减** `stock`（未支付也扣），超时取消再 `restoreStock` 加回。
   ⇒ `stock` 数字里**混着「未付款订单占用的量」**，运营看到 `stock = 0` 会以为真卖完了。

### 决策（2026-10-09）

- **单仓**（符合单店模式，不需要 warehouse / sku_stock 表）。
- **新增 `stock_log` 库存流水表**（append-only）。
- **本轮不做「锁定库存」**（拆 `stock` / `locked_stock` 会波及订单、支付、超时取消三处，留作后续）。

### `stock_log` 设计要点（详见计划文档项 3）

- **append-only**：只插入，**不更新不删除** ⇒ 不需要 `is_deleted` / `version`（与项目其他表不同）。
- **同时存 `before_stock` 与 `after_stock`**：只存 `change_qty` 的话，中间错一条后面全错、无法定位；
  存了前后值就能逐条核对（上一条的 `after` 是否等于下一条的 `before`）。
- **冗余 `product_id`**：按商品维度查流水不用 join。
- **`biz_no` 关联订单号**：这是「按订单追溯库存变化」的钥匙。
- ⚠️ **写入时机（易算错，必须注意）**：`deductStock` 是条件更新，**MySQL 的 UPDATE 不返回旧值**。
  正确做法是在**同一事务内**先 `deductStock`、成功后 `selectById` 拿 `after_stock`，
  `before = after + quantity`。因为刚更新过该行、本事务持有行锁，读到的值准确。
  **前提是扣减与回读必须在同一事务**（`OrderServiceImpl.create()` 是 `@Transactional`，满足）。
- ⚠️ 新增表 ⇒ **Flyway 新增 V9**（本项目第一次在 V8 之后加），
  **必须同步 `migration-test` 副本，且索引名全局唯一**（CI 红线）。

## 🔴 数据模型对齐 V9（2026-10-09 落地）—— **取代上文「库存模型」的「加列」方案**

**用户 2026-10-09 给出目标数据模型并要求对齐落地**：
`product` / `sku`（含价格、规格 JSON）/ `product_attribute` + `product_attribute_value` + `sku_spec_value`
（规格体系）/ `inventory`（**sku_id 唯一**）/ `inventory_log` / `product_image` / `category` / `brand` / 订单快照。
六条核心原则：① SPU 管展示 SKU 管交易 ② 库存独立表 + 流水 ③ 规格关系表 + JSON 冗余
④ 金额用 decimal ⑤ 库存条件更新防超卖 ⑥ 订单必须快照。

**对照结论（2026-10-09 实测）**：六条中 **4 满足**（①④⑤⑥）、**2 不满足**（②③）；
表级 4 满足 / 1 部分 / 6 缺失（缺 `product_attribute*` ×3、`inventory`、`inventory_log`、`brand`）。

**⚠️ 重要决策变更**：此前（15:10）设计的是「在 `product_sku` 上加 `locked_stock` 列」；
用户要求「向参考模型对齐」⇒ **改为 `inventory` 独立表（sku_id 唯一）**。**加列方案作废。**

**已落地（第 1 阶段，Flyway V9 + 6 实体，纯增量、未改任何业务逻辑）**：

| 表 | 说明 |
|---|---|
| `brand` | 基础资料，`uk_brand_name` |
| `product_attribute` | 属性定义，挂 `category_id` |
| `product_attribute_value` | 可选值（列名 **`attr_value`**） |
| `sku_spec_value` | SKU ↔ 属性值；**纯关联表**（物理删除，无 is_deleted/version，同 `admin_user_role`） |
| `inventory` | **sku_id 唯一**；`stock` 在库实物量 + `locked_stock` 锁定中；可售 = stock − locked |
| `inventory_log` | append-only，**无 is_deleted/version**（同 `operation_log`） |

另：`ALTER TABLE product ADD COLUMN brand_id`；存量 `product_sku.stock` 已一次性迁入 `inventory`。

**库存三量语义**：`stock`（在库实物量）/ `locked_stock`（未付款订单锁定）/ `available = stock − locked`（可售）。
⚠️ **`stock` 语义从「可售（下单即扣）」变为「实物在库」**，前端展示与下单校验都要改用 `available`。

**写迁移的三个坑（下次直接用）**：
1. **`VALUE` 是 H2 关键字** ⇒ 列名必须改（本项目用 `attr_value`），否则 H2 建表失败。
2. **`migration-test` 副本的 `ALTER` 要 H2 兼容**：不能用 `AFTER`，也不能用 `ADD KEY`
   ⇒ 拆成 `ADD COLUMN` + `CREATE INDEX`（对照 V6 副本）。
3. **索引名前缀化、全局唯一**，主脚本与副本保持一致，免去改名（CI 红线）。

**待办（第 2 阶段起）**：Mapper/Service/VO/Controller（含条件更新锁定/出库/释放 + 同事务写流水）；
业务调用点切换（下单/支付/取消/后台/导入）。✅ 以上均已完成；
**`product_sku.stock` 迁移期镜像列已于 2026-10-09 由 V10 删除**（见下方 V10 一节）；
`specs` → 属性三表的历史数据回填（需应用层解析）仍未做。

**验证**：`mvn -pl spring-shop-web -am test -Dtest=ProductIntegrationTest` → v9 应用成功、6 用例全绿、BUILD SUCCESS。

### ✅ 第 2 阶段已完成（2026-10-09 15:45）—— 6 张新表都有访问层了

- **库存域**：`InventoryMapper`（4 个条件更新：`lockStock`/`releaseLock`/`outbound`/`adjustStock`，
  全部带 `is_deleted = 0` + `version = version + 1`）、`InventoryLogMapper`、
  `InventoryService`/`Impl`（`initStock`/`lock`/`outbound`/`release`/`adjust`/`getBySkuId`/`pageLogs`）、
  `InventoryVO`/`InventoryLogVO`/`InventoryLogQuery`/`InventoryAdjustRequest`、
  `AdminInventoryController`（`/api/admin/inventory/skus/{skuId}[/logs]`、`PUT .../stock`）。
  错误码 **2030 / 2031 / 2032**。
- **品牌 + 属性域**：`BrandMapper`/`ProductAttributeMapper`/`ProductAttributeValueMapper`/`SkuSpecValueMapper`
  （后者带 `deleteBySkuId` + `insertBatch`）、`BrandService`/`Impl`、`AttributeService`/`Impl`、
  `AdminBrandController`（`/api/admin/brands`）、`AdminAttributeController`（`/api/admin/attributes`
  + `/{id}/values[/{valueId}]`）。错误码 **2040~2042 / 2050~2056**。
- **权限**：`AdminDataInitializer` +8 项（`product:brand:*`、`product:attribute:*`），
  **ADMIN 菜单 34 → 42**。新增 `@PreAuthorize` 必须同步注册，否则 `AdminPermissionCoverageIntegrationTest` 直接红。
- **两条守卫**（写在代码注释里）：属性删除先卡「有可选值」（`PRODUCT_ATTRIBUTE_HAS_VALUES`），
  再卡「被 `sku_spec_value` 引用」（`PRODUCT_ATTRIBUTE_IN_USE`）；可选值删除卡 `PRODUCT_ATTRIBUTE_VALUE_IN_USE`。
  **可选值归属校验要落在 SQL 条件里**（`eq(id).eq(attributeId)` 一起查），不要查出来再比较。
- **仍未做**：第 3 阶段业务调用点切换（下单/支付/取消/后台/导入）。它依赖 15:10 设计里
  **用户尚未拍板的 4 个决策**，动手前必须先问。

### ✅ 第 3 阶段已完成（2026-10-09 16:00）—— `inventory` 成为库存唯一真相源

**🔴 当前库存口径（写代码前必读）**：
- `inventory.stock` = **在库实物量**；`inventory.locked_stock` = 未付款订单锁定；**可售 = 两者之差**（派生，不落库）。
- **下单只 lock**（在库不变、锁定 +）；**支付 outbound**（在库与锁定同时 −）；**取消/超时 release**（锁定 → 可售）。
- **`product_sku.stock` 已无人维护**：订单链路完全不碰它，只在建 SKU 时写一次。
  ⇒ **对它做断言 = 断言一个常量**，读路径与测试都必须改读 `inventory`。
- **读路径一律用 `InventoryService.available()/availableMap()`**（= `max(stock − locked, 0)`），
  **库存行缺失返回 0 而不是抛异常**（fail-closed：一条脏数据不该把详情页打成 500）。
- 业务侧的锁/出/释**全部是条件更新**：0 行 = 条件不满足 ⇒ 抛业务异常。
  库存域错误码（2030/2031）在 `OrderServiceImpl.lockStock` 里**映射成订单域 4004**，保持前端契约不变。

**本轮顺手修掉的并发缺陷**（不是新功能，是结构性保证）：
`pay()` / `cancel()` 原先是 `requireOrder` + `updateById`，并发不安全。
现改为条件更新（`OrderMapper.markPaid` / `cancelIfPendingPayment`），
⇒ **重复支付不会重复出库、重复取消不会重复释放**。

**改动清单**：`InventoryService(+Impl)` 加 `available/availableMap`；
`OrderServiceImpl`（orderNo 提前生成、三个私有库存方法、pay/markPaid/cancel 改条件更新）；
`CartServiceImpl`（校验与 VO 改读 available/availableMap）；`ProductQueryServiceImpl`（详情 stock 取 availableMap）；
`ProductManageServiceImpl` / `ProductImportServiceImpl`（建 SKU 后 `initStock`；导入因 `insertBatch`
不回填主键，**按 sku_code 回查**取 id）；`ProductSkuMapper` **删除 `deductStock`/`restoreStock`**。

**测试侧才是真正的工作量（这是本轮最大的教训）**：
搬家后**所有「手工 INSERT 老表数据」的测试都成了假数据** ——
`OrderIntegrationTest` / `CartIntegrationTest` / `PayIntegrationTest` 的 `createSku(...)`
绕过业务创建路径，**必须补插 `inventory` 行**，否则 fail-closed 的可售量 = 0，
加购/下单全部报「库存不足」（症状看着像业务 bug，其实是测试数据缺失）。
纯 Mockito 侧要补 `@Mock InventoryService` + stub。
⇒ **找全测试的姿势**：按老列 setter（`setStock(`）**全仓库 grep**，调用点散在 `spring-shop-web`。
⇒ 详见技能 `spring-shop-add-backend-domain` 的「把某字段的真相表换掉时」一节。

**⚠️ 未经用户逐条确认的默认值（已在汇报里标出，等改口径）**：
1. **出库时机 = 支付成功**（不是发货）；
2. ~~`product_sku.stock` 暂留为迁移期镜像~~ ✅ **已由 V10 删除（2026-10-09）**；
3. `specs` → 属性三表的**历史回填暂缓**（老 SKU 仍以 `specs` JSON 为准）。

**✅ V10 已落地（2026-10-09）**：`product_sku.stock` 已删除。原先的前置阻塞
（`saveSkus` 走「整批逻辑删除 + 重建」会让 `inventory` 变孤儿行）已随商品域收口的
「按规格就地更新」改造一并解除，所以 V10 得以执行。

### V10：删 `product_sku.stock`（2026-10-09）—— 两条必须记住的规则

1. **已发布的 Flyway 脚本一个字节都不能改。** 校验和写进了 `flyway_schema_history`，
   改 V2/V9 ⇒ 所有已跑到 V9 的库（含本机 dev）**启动即校验失败**。
   正确做法是**纯增量**：V2 建列 → V9 回填 → V10 删列，新库与老库最终结构一致。
   （`application.yml` 用 Flyway 默认 `validate-on-migrate=true`，所以这不是风格问题而是硬约束。）
2. **DROP 前必须先跑一致性检查**（结果必须 0 行）：
   ```sql
   SELECT s.id, s.sku_code, s.stock AS sku_stock, i.stock AS inv_stock
   FROM product_sku s LEFT JOIN inventory i ON i.sku_id = s.id
   WHERE s.is_deleted = 0 AND (i.id IS NULL OR s.stock <> i.stock);
   ```
   有行 ⇒ 还有写入路径在改那一列，DROP 会静默丢库存信息。校验**刻意没写进脚本**：
   脚本要同时跑 MySQL 8 与 H2，「不一致即中止」需要 `SIGNAL`/存储过程，两方言不通。

**实现要点**：库存不能挂在 `ProductSku` 上了，但建 `inventory` 行必须在 SKU 拿到自增 id
**之后**（`insertBatch` 是自定义 `@Insert`，不回填主键，得按 `sku_code` 回查）。
中间这段路用 `ProductImportServiceImpl` 内的 `record SkuDraft(ProductSku sku, int stock)` 承载。

## 已收口的三个待办项（2026-10-09）

| 项 | 状态 |
|---|---|
| 商品唯一性规则 + 修 3 个同源 bug + 删除接口 | ✅ 已落地（409 用例） |
| V10 删除迁移期镜像列 `product_sku.stock` | ✅ 已落地（代码侧；**dev/生产库尚未执行迁移**） |
| 项 1a 定时任务执行日志查询 `GET /api/admin/stats/task-logs` | ✅ 已落地（424 用例） |

**明确不做**：项 1b 失败告警（缺外部 webhook，做了也只是假告警）；
项 3 原方案（`stock_log` 表）已被 V9 的 `inventory_log` 取代，**不要重启**。

### 后台只读查询接口的标准配方（已出现 4 次，照抄即可）

`PageQuery` 子类（`current` / `size` + 业务过滤字段）→ Service 返回
`PageResult<XxxVO>`（**不返回 entity**，AGENTS 红线）→ `@ParameterObject @Valid` 绑定
→ `@PreAuthorize` 权限码 → **在 `AdminDataInitializer.ensureMenu` 幂等注册**（漏了就是永久 403）。

两个细节：
- **日期区间一律用半开区间** `[startDate 00:00, endDate+1 00:00)`。写 `endDate 23:59:59`
  要猜列精度，还会漏掉当天最后一秒的记录。
- **过滤列要挑有索引的那个**。`stats_task_log` 只有 `(task_name, start_time)`，
  所以按执行时间过滤而不是业务日期 `stat_date`。

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

### 购物车缓存 `cart:{userId}`

`CartServiceImpl` 自己的写路径（`add` / `updateQuantity` / `updateChecked` / `delete` /
`deleteChecked` / `clear`）都会维护 `RedisKeys.cart(userId)`；
**但绕过它、直接操作 `cartItemMapper` 的地方必须自己补失效**。
已知会绕过它的地方：`OrderServiceImpl.create()`（下单删勾选行）——
**已修并已合入 main**（`895b976`，合并提交 `10a9864`，2026-10-08）。
新增任何直接操作 `cart_item` 的代码时，先想一遍要不要失效 `cart:{userId}`。

### 商品详情缓存 `product:detail:{productId}`（2026-10-09 补齐）

**关键前提**：详情 VO 里带着每个 SKU 的**可售量**（`stock − locked_stock`）与商品**销量**，
所以「凡改变可售量或销量的写路径都必须失效它」。当前 TTL 30 分钟 + 随机抖动。

| 写路径 | 谁失效 | 状态 |
|---|---|---|
| `ProductManageServiceImpl.update` / `updateStatus` | 自己 | ✅ |
| `OrderServiceImpl.create()`（锁定 + `increaseSales`） | 自己 | ✅ |
| `OrderServiceImpl.cancel()` / `systemCancel()`（释放 + `decreaseSales`） | 自己 | ✅ |
| **`InventoryServiceImpl.adjust()`（后台调在库量）** | **自己** | ✅ **2026-10-09 补**（此前漏了，后台改完库存前台最多 31 分钟看旧值） |
| `OrderServiceImpl.pay()` / `markPaid()`（出库） | **不需要** | ✅ 刻意不做，见下 |

⚠️ **`outbound` 不需要失效，这是不变量不是遗漏**：
可售量 = `stock − locked_stock`，出库时两者同时减 q ⇒ 差值恒等（`(S−q) − (L−q) = S − L`）；
支付也不改销量。**不要为了「对称」给它加失效**（会平白给支付热路径引入 Redis 依赖）。
已用测试把「不做」钉住：`InventoryServiceImplTest#outbound_shouldNotEvictDetailCache_becauseAvailableIsInvariant`。

**新增库存/商品写路径时，先对照上表问一句：这条路径改了可售量或销量吗？改了就要失效。**

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

`main` **领先 `origin/main` 3 个提交（未 push）**，`origin/main` 仍是 `ad1cbdb`
（2026-10-09 17:20 实测核对）。领先的 3 个提交是本轮商品域与 stats 收尾：

| 提交 | 内容 | 独立验证 |
|---|---|---|
| `a18a1d9` | `feat(product): 数据模型对齐 V9 + 商品唯一性规则 + V10 收口` | 409 用例绿 |
| `777c458` | `feat(stats): 任务执行日志查询接口` | 424 用例绿 |
| `fadab7d` | `docs: 同步模块文档与收尾计划` | 纯文档 |

⚠️ 用户只说「提交」，**没说 push**，所以只 commit 了。要推的话先问。

> **为什么没有拆成 3 个提交**：V9 / 商品唯一性 / V10 三者在本轮代码里**在 hunk 级别也是交织的**
> —— 例如 `CartServiceImplTest` 同一个 hunk 内既有 V10 的辅助方法签名变更（`sku(...)` 去掉
> stock 参数），又有 V9 的 `when(inventoryService.available(...))` 打桩。`git add -p` 切不开，
> 只能逐行重写中间态（人工构造、从未真实存在）。因此合并为 `a18a1d9` 一个提交。
> **拆分时每个提交都单独跑过 `mvn clean test`**（把其余改动 `git stash -u` 起来验证）。

⚠️ **此前记录的「领先 5 个提交未 push」已作废**：那 5 个提交（`839c507` A4+A7 ·
`e221327` A2 · `1d646f9` A1 · `5a3e6cf` B1 · `bf107f8` A3）**已经 push 到 origin/main**，
之后又追加了 `ad1cbdb`（内部文档 + 定时任务计划同步）。工作区 clean，无未提交改动。
（2026-10-09 17:20 实测核对。）

### 🔴 分支一律不删，也不再列为待办（用户 2026-10-09 明确要求）

**永远不要删除本仓库的任何分支（本地 + 远端都不删）**，无论它是否已合并、是否看起来冗余。
已合并的分支**长期保留**，这是用户的明确决定。

**同时：不得再把「清理分支 / 冗余分支 / 分支待清理」写进任何计划、缺口清单、建议或决策点。**
历史上我把这件事当待办列过（编号 E1），已被用户否掉，**不要再提**。

现有 4 个功能分支（**均已合入 main，且分支独有提交数为 0，但依然保留**）：
`fix/cart-cache-invalidate-on-order`（`895b976`，合并提交 `10a9864`）·
`feature/order-timeout-cancel` · `feature/observability` · `feature/admin-user-log-import`。
此清单仅作「仓库里有哪些分支」的事实记录，**不含任何清理建议**。

> 唯一一次分支删除是 `feature/mcp-experiment`（实验四 Spring AI 进程内 MCP Server，`32fd2b5`），
> 已于 2026-10-08 按用户当时的明确要求删除，并一并清理了 Go 工具链（271MB）、Go 缓存（154MB）、
> mcp-link 产物（15MB）。**该授权不适用于其它分支，不要再引用它作为删分支的先例。**

## 用例数口径（易搞错，记一次）

**全量 `mvn test` = 424 个用例**：common 38 / user 17 / admin 28 / product 95 / cart 38 /
order 33 / pay 7 / stats 10 / web 143（2026-10-09 17:20 实测，BUILD SUCCESS）。

口径演进：2026-10-08 基线 **287** → 六项修复 +37 = **324** → V9 对齐第 1+2 阶段 +45 = **369**
→ 第 3 阶段（库存调用点切换）+4 = **373** → 详情缓存失效补口 +1 = **374**
→ 商品域收口（唯一性 + Bug A/B/C + 删除接口）+35 = **409**
→ V10 删 `product_sku.stock`（只删字段不删测试）±0 = **409**
→ 项 1a 任务执行日志查询接口 +15（stats +8 / web +7）= **424**
（product 73 → 95：`SkuSpecNormalizerTest` 9 + `ProductManageServiceImplTest` +9 +
`ProductImportServiceImplTest` +4；web 130 → 143：新建 `ProductUpdateIntegrationTest` 12 +
`ProductImportIntegrationTest` +1。

⚠️ Maven 每个模块各打印一次 `Tests run: N`，**最后一行只是最后一个模块（web = 143）**，
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
     ⚠️⚠️ **2026-10-09 读码新发现（比上面的并发窗口严重得多）**：
     **后台手工新增/修改商品路径对商品名零校验** ——
     `ProductManageServiceImpl.validateSkuCodesUnique()` 只校验 `sku_code`，
     `buildProduct()` 直接把 `request.getName()` 写入。⇒ **无需任何并发**，
     运营在后台「新增商品」里手填一个已存在的名字，就能直接造出重名 SPU。
     A1 修的只是**导入**这一条路径，后台这条捷径一直敞着。
     ⇒ 「商品名唯一性」的真实缺口是**两条**：① 后台手工新增（现实、必然发生）；
     ② 导入并发窗口（极窄）。**要补应先补 ①**，成本极小（与导入共用
     `ProductMapper.selectOccupiedProductNames`，不碰库表）。
     库级方案重新评估后结论：**裸加 `UNIQUE(name)` 不可取**（删掉的商品永久占名，
     且与导入侧 `is_deleted = 0` 口径冲突）；**推荐「生成列 + 唯一索引」**
     （`name_active AS (IF(is_deleted = 0, name, NULL))` STORED，NULL 不互相冲突）
     —— 但**必须先验证 H2 是否支持该 DDL**，测试库是 H2 MySQL 模式。
     完整方案对比见 `docs/superpowers/plans/2026-10-09-next-round-hygiene-plan.md` 项 2。
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
C3 定时任务平台化 · C4 Docker 交付 · D 组基础设施。

> ⚠️ 原列在此处的「E1 清理 4 个已合并的冗余分支」**已永久移除**：
> 用户 2026-10-09 明确分支永不删除，且**不得再把分支清理列为待办**。

### 已否决，勿重启

| 项 | 否决时间 | 说明 |
|---|---|---|
| REST→MCP | 2026-10-08 | Go 与 Java 栈不符；进程内方案拿不到 JWT 做不了写。详见上方「MCP 方案」节 |
| **环境统一（`.mvn/wrapper` + `.java-version`）** | **2026-10-09** | 用户原话「**不做，去掉这个任务**」。**不要再提议引入 Maven Wrapper，也不要再写进任何计划/清单。** 相关计划文档 `2026-10-09-next-round-hygiene-plan.md` 已删除该项；`docs/collaboration-plan.md` 该行状态已改为「已否决，不做」 |

## 写单测时的坑（2026-10-09 踩到，别再踩）

- **纯 Mockito 单测里 MP 的 lambda wrapper 渲染出的是属性名不是列名**：手工预热 `TableInfo`
  时没有 MP 的 `GlobalConfig`，`getSqlSet()` 会给出 `errorMsg=#{...}` 而非 `error_msg=#{...}`。
  ⇒ 断言写成 `sqlSet.replace("_","").toLowerCase().contains("errormsg")`，
  「列名到底对不对」交给 H2 集成测试（`ExcelTaskCasIntegrationTest`）。
- **⚠️ 纯 Mockito 单测里服务层只要构造 lambda wrapper，就必须 `@BeforeAll` 预热 `TableInfo`**，
  否则会抛 `MybatisPlusException: can not find lambda cache for this entity [Xxx]`。
  项目既有写法（对齐 `ProductQueryServiceImplTest`）：
  ```java
  @BeforeAll
  static void warmupMybatisPlusLambdaCache() {
      for (Class<?> entityClass : new Class<?>[]{ A.class, B.class }) {
          try {
              TableInfoHelper.initTableInfo(
                      new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
          } catch (Exception ignore) { /* 尽力而为 */ }
      }
  }
  ```
  **别赌 MP 会自己初始化。** 2026-10-09 的实证：同一个测试类里走 `eq()` 的用例是绿的，
  只有走 **`in(...)`**（`AbstractWrapper.in` → `columnToMapping` → `getColumnCache` → `tryInitCache`）
  的用例炸了 —— **同一实体、同一类，只因为走了 `in` 就暴露**，非常难凭直觉发现。
- **`verify(mapper).insert(any())` 在 MP 3.5.17 编译不过**：`BaseMapper` 同时有
  `insert(T)` 和 `insert(Collection<T>)`，`any()` 歧义（报「对 insert 的引用不明确」）。
  ⇒ 必须写 `insert(any(XxxEntity.class))`。
- **`getParamNameValuePairs()` 是 public 的**，用它断言「恰好 N 个绑定值为 null」可以证明
  `wrapper.set(..., null)` 真的生效（`NOT_NULL` 策略下参数根本不会被注册）。
- **⚠️ MP 3.5 的参数绑定是惰性的：读 `getParamNameValuePairs()` 之前必须先调一次 `getSqlSegment()`**。
  2026-10-09 项 1a 实测：`ArgumentCaptor` 抓到的 wrapper 直接读 `getParamNameValuePairs()` **永远是空 Map**，
  症状是 `expected: <1> but was: <0>` —— 看起来像「一个过滤条件都没加」，极易误判成业务代码漏加条件。
  正确写法（`TaskLogQueryServiceImplTest#boundValues`）：
  ```java
  LambdaQueryWrapper<StatsTaskLog> wrapper = (LambdaQueryWrapper<StatsTaskLog>) captor.getValue();
  wrapper.getSqlSegment();            // ← 必须先渲染，参数才会被注册进 paramNameValuePairs
  return wrapper.getParamNameValuePairs().values();
  ```
  **它与上面的 `TableInfo` 预热是两件事，缺一不可**：不预热 → `can not find lambda cache`；
  预热了但不渲染 → 条件「凭空消失」。调试时先 `System.out.println(wrapper.getSqlSegment())`，
  能一次分辨到底是哪一种（渲染出来的 SQL 段本身就是最好的证据）。
- **`-Dsurefire.failIfNoSpecifiedTests=false`** 才是正确属性名（不是 `-DfailIfNoSpecifiedTests`）。
- **⚠️ `mvn test-compile` 可能是假成功**：增量编译器会跳过未变更的测试类，
  **真的编译错误也能报 BUILD SUCCESS**。改完测试源码后一律用 **`mvn clean test-compile`** 验证
  （2026-10-09 实测踩到：非 clean 跑出 SUCCESS，clean 后立刻暴露 `找不到符号`）。
- **⚠️ Mockito 的默认返回值会「静默」改变用例结论**：给被测服务新加一个依赖后，
  忘了 `@Mock` 是 NPE（显眼），但**补了 `@Mock` 却忘 stub** 才是阴的 ——
  - 返回 **`Map`** 的方法 → 默认**空 Map** ⇒ 用例**悄悄变绿**（看起来像"按 0 处理"的正常行为）；
  - 返回 **`int`** 的方法 → 默认 **0** ⇒ 「本该成功」的用例**静默变红**
    （典型症状：`expected BusinessException but was NullPointerException`，
    因为依赖是 null 时连 stub 都没生效）。
  ⇒ 新增依赖后要逐个确认**哪些方法需要 stub**，不能只看「有没有 NPE」。
- **新增 `@PreAuthorize` 权限码必须同步注册**：`AdminPermissionCoverageIntegrationTest` 会反射
  扫出容器里所有 `@PreAuthorize` 的 authority，和 `menu` 表（以及 ADMIN 角色授权）对账，
  漏注册 / 漏授权都直接红。注册点在 `AdminDataInitializer#initMenus`，幂等键是 `permission_code`。
- **⚠️ 单模块跑测试必须带 `-am`，否则会被 `~/.m2` 里的旧快照 jar 骗**：`mvn -pl spring-shop-product test`
  会从 `~/.m2/repository/com/springshop/**` 解析兄弟模块 jar。若那是旧版本，症状是
  **一堆看起来毫不相干的 `NoSuchMethodError` / `NoSuchFieldError`**
  （2026-10-09 实测：`ExcelReadOptions.expectedHeaders`、`ResultCode.PRODUCT_ATTRIBUTE_VALUE_IN_USE`），
  极易误判成「自己的改动搞坏了 common」。⇒ 加 `-am` 或直接跑全量 reactor。
- **⚠️ 改校验顺序会顺手制造 `UnnecessaryStubbing`**：严格模式下「某个桩没被用到」直接红。
  2026-10-09 实测：把唯一性校验插到分类校验之前后，「SKU 编码重复」用例里那个
  `categoryMapper` 桩就再也不会被调用了。⇒ 调整校验顺序时，**同步清理下游用例里失效的桩**。
- **测试里的「不该发生」要用 `never()` 显式钉住**：新语义常见「复用已有 SPU 时**不该**新建」
  （`verify(productMapper, never()).insertBatch(any())`）—— 只断言「结果对」会被
  「碰巧也建了一个 SPU」蒙混过去。

## 🔴 Flyway：已应用的迁移脚本一个字节都不能改（2026-10-09 实证踩到）

`application.yml` 用 Flyway 默认的 `validate-on-migrate=true`，每个已应用脚本的校验和
存在 `flyway_schema_history` 里。**改已应用脚本 ⇒ 所有跑过它的库「启动即校验失败」。**

2026-10-09 实际踩到：V9 在 15:25:00 应用到 dev 库后，脚本又被重写了
（mtime 15:25:11 / 15:25:14），把 `product_attribute_value.value` 改成 `attr_value`
（因为 `VALUE` 是 H2 关键字）。后果：**dev 应用直接起不来**，且属性值查询会
`Unknown column 'attr_value'`。**当时谁都没发现，因为测试跑的是 H2 全新库。**

> 记忆点：**脚本 mtime 比 `installed_on` 晚 ⇒ 一定被改过。** 这一条就能定性。

### 诊断「库结构与迁移脚本不一致」的标准做法（可复用）

建一个**全新对照库**跑完整迁移，再和问题库逐表 diff —— 不用启动应用，不用猜改了哪几行：

```bash
mysql -uroot -proot -e "CREATE DATABASE spring_shop_check CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
# 用 Flyway Java API 把它迁移到目标版本（见下）
norm() { mysqldump -uroot -proot --no-data --skip-comments --skip-dump-date --skip-set-charset \
  --default-character-set=utf8mb4 "$1" | grep -vE '^/\*!|^--|^$' | sed -E 's/ AUTO_INCREMENT=[0-9]+//'; }
diff -u <(norm spring_shop_check) <(norm spring_shop)
mysql -uroot -proot -e "DROP DATABASE spring_shop_check;"   # 用完即删
```

噪声提示：`flyway_schema_history` 自身的 collation 常因建库时默认排序规则不同而 diff 出来，
**与业务无关，忽略**。

### 修复「脚本应用后被改」的三步

1. 把问题库结构改成与新脚本一致（`ALTER TABLE ...`）；
2. `flyway repair` —— 重写校验和为当前文件值（官方补救手段，**会改写迁移历史，必须先跟用户说明**）；
3. `flyway migrate` —— 继续应用后续版本。

⚠️ **只 repair 不修结构是错的**：校验绕过了，但库里列名依旧旧，运行时才炸。

### 不启动 Spring 容器跑迁移 / repair 的办法

启动应用有副作用（`OrderTimeoutTask` 取消超时单、`AdminDataInitializer` 写 menu）。只想跑迁移时：

```bash
mvn -q -pl spring-shop-web dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -DincludeScope=runtime
java -cp "$(cat /tmp/cp.txt)" Migrate.java <db> <migrate|repair|validate|info> [target]
```

`Migrate.java` 用 Flyway 官方 API + Java 21 单文件源码启动；classpath 需含
`flyway-core` + `flyway-mysql` + `mysql-connector-j`。

### ⚠️ `MAX(version)` 陷阱

`flyway_schema_history.version` 是 **VARCHAR**，`MAX()` 走字典序 ⇒ `'9' > '10'`，
**明明到了 V10 也返回 9**。查最新版本一律 `ORDER BY installed_rank DESC LIMIT 1`。

### dev 库当前状态（2026-10-09 17:10）

**已在 V10**（rank 11 / version 10 / `success=1`），`product_sku.stock` 已删，
且与「全新 V1→V10」库 **0 差异**；V9 校验和已 repair 为 `-2029679033`。
迁移前备份：`/tmp/spring_shop_backup_before_v10.sql`（⚠️ `/tmp` 会被系统清理）。

**V9 / V10 仍未进 git**（只有 V1–V8 被跟踪）。提交后，**任何在 15:25:11 之前跑过旧版 V9
的库都会遇到同样的启动失败**，需按上面三步修复。生产库尚未接触。

