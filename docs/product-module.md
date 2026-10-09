# 商品模块（spring-shop-product）技术梳理

本文档梳理 `spring-shop-product` 模块的 MVP 实现，包含模块职责、分层、数据模型、关键服务、接口、测试基座与后续可扩展点。

## 一、模块职责与边界

**模块名**：`spring-shop-product`，隶属 spring-shop 单体业务模块之一。

- **上游依赖**：`spring-shop-common`（统一响应、JSR303、MyBatis-Plus、JWT/安全注解）
- **下游被依赖**：`spring-shop-web`（启动模块），`spring-shop-web` 负责把 product 模块的控制器扫入 Spring 容器并加载 Mapper。
- **禁止依赖**：`spring-shop-admin`、`spring-shop-user`（保持模块单向依赖 `web → product → common`，避免环）
- **当前范围**：
  - 分类子域：后台 CRUD + 前后台分类树（分类树走 Redis 缓存）
  - 商品子域：SPU/SKU/Image 写服务 + 读服务聚合 + 前后台列表/详情接口（详情走 Redis 缓存）
  - 批量能力：Excel **异步**导入（部分成功 + 失败明细下载）与**异步**导出（边查边写，四类导出之一）

## 二、目录与分层结构

```
spring-shop-product/
└── src/main/java/com/springshop/product/
    ├── category/            # 分类子域
    │   ├── controller/admin/AdminCategoryController.java
    │   ├── controller/app/AppCategoryController.java
    │   ├── dto/CategorySaveRequest.java
    │   ├── entity/ProductCategory.java
    │   ├── mapper/ProductCategoryMapper.java
    │   ├── service/{CategoryService.java, impl/CategoryServiceImpl.java}
    │   └── vo/{CategoryVO.java, CategoryNodeVO.java}
    └── product/             # 商品子域
        ├── controller/admin/AdminProductController.java
        ├── controller/app/AppProductController.java
        ├── dto/{ProductSaveRequest.java, ProductPageQuery.java, ProductSkuItem.java, ProductImageItem.java}
        ├── entity/{Product.java, ProductSku.java, ProductImage.java}
        ├── mapper/{ProductMapper.java, ProductSkuMapper.java, ProductImageMapper.java}
        ├── service/{ProductManageService.java, ProductQueryService.java}
        ├── service/impl/{ProductManageServiceImpl.java, ProductQueryServiceImpl.java}
        └── vo/{ProductListVO.java, ProductDetailVO.java, ProductSkuVO.java, ProductImageVO.java}
```

- 严格按 AGENTS.md 分层：`controller → service(interface + impl) → mapper/entity`，入参 DTO、出参 VO。
- Entity 不对外返回，Controller 只组装 `Result<T>`，业务失败统一抛 `BusinessException`。

## 三、数据模型（复用 V2 Flyway 脚本）

表：`product_category / product / product_sku / product_image`（定义在 [V2__init_product_schema.sql](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/resources/db/migration/V2__init_product_schema.sql)）

> **V9 起新增**（见 [V9__product_model_alignment.sql](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/resources/db/migration/V9__product_model_alignment.sql)）：
> `brand`（+`product.brand_id`）、`product_attribute` / `product_attribute_value` / `sku_spec_value`（规格体系）、
> **`inventory`（sku_id 唯一）/ `inventory_log`（流水）**。
>
> **V10 起删除**（见 [V10__drop_product_sku_stock.sql](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/resources/db/migration/V10__drop_product_sku_stock.sql)）：
> `product_sku.stock` —— V9 留下的迁移期镜像列，业务写入路径全部切到 `inventory` 后已无读者。
>
> ⚠️ **V2 与 V9 脚本刻意不动**：它们是已发布脚本，改一个字节就会改 Flyway 校验和，
> 让所有已经跑到 V9 的库启动即失败。新增库依次走 V2（建列）→ V9（回填）→ V10（删列），
> 最终结构与老库一致。**DROP 前请在目标库跑一次一致性检查**（脚本头注释里有现成 SQL），
> 结果必须 0 行，否则说明还有写入路径在改那一列。

- **product_category**：parent_id=0 表示根，sort 越小越靠前；逻辑删除 + 乐观锁 version。
- **product**（SPU）：价格与库存不落此表，主图、详情、销量、上下架状态 status=1 上架。
- **product_sku**：**价格的唯一事实源**；`sku_code` 数据库唯一约束 `uk_sku_code`。
  ⚠️ **库存不在它这里**（V9 起迁到 `inventory`）：`product_sku.stock` 这个迁移期镜像列
  已由 **V10 删除**，实体上不再有 `stock` 字段。**读库存一律走 `InventoryService`**。
- **inventory**：单仓，`sku_id` 唯一；`stock` 在库实物量 / `locked_stock` 未付款订单锁定 /
  **可售 = 两者之差**（派生）。所有增减都是条件更新（0 行 = 条件不满足）。
  > **详情缓存失效的责任边界（易漏）**：`product:detail:{id}` 里带着每个 SKU 的**可售量**，
  > 所以凡改变可售量的库存变更都要失效它。现状：
  > `lock` / `release` 由 `OrderServiceImpl`（create / cancel / systemCancel）失效；
  > `adjust` 没有调用方兜底，**由 `InventoryServiceImpl` 自己失效**；
  > `outbound`（支付出库）**不需要** —— `stock` 与 `locked_stock` 同时减 q，可售量恒定不变。
  > 改动库存写路径时先对照这三条，别漏也别加多余的失效。
- **product_image**：`sort` 控制详情图集展示顺序。

MVP 关键约束：

1. 删除分类前需校验是否存在子分类或该分类下有商品（否则抛 2002/2003）。
2. 分类更新时禁止将自己或自己的子孙节点设为父节点（避免环）。
3. SKU 列表非空（2013），`sku_code` 在**全表**范围内唯一且排除自身后唯一（2012）。
4. 前台仅能查询上架商品；下架商品详情访问直接返回 2014 `PRODUCT_OFF_SHELF`。

## 四、错误码段位

[ResultCode.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java) 商品模块占用 2000~2099：

```
2001 PRODUCT_CATEGORY_NOT_FOUND
2002 PRODUCT_CATEGORY_HAS_CHILDREN
2003 PRODUCT_CATEGORY_HAS_PRODUCTS
2010 PRODUCT_NOT_FOUND
2011 PRODUCT_SKU_NOT_FOUND
2012 PRODUCT_SKU_CODE_DUPLICATE
2013 PRODUCT_SKU_EMPTY
2014 PRODUCT_OFF_SHELF
2015 PRODUCT_IDENTITY_DUPLICATE   # 同名同规格的商品已存在（(名称, 规格) 唯一）
2016 PRODUCT_NOT_OFF_SHELF        # 商品未下架，不可删除
2020 PRODUCT_IMPORT_FILE_INVALID
```

## 五、关键服务设计

### 5.1 CategoryService

- `create / update / delete / getById / tree`
- 删除流程（见 CategoryServiceImpl.delete）：
  1) 先根据分类 id 查是否存在；
  2) 查 `parent_id=id` 子分类数量，>0 抛 2002；
  3) 查 `product.category_id=id` 的商品数量，>0 抛 2003；
  4) 再执行 MyBatis-Plus 逻辑删除。
- 更新流程：构建「祖先链」禁止挂到自己/子孙节点下。
- `tree()`：查所有启用分类（status=1, not deleted），先按 parent_id 建 childrenMap，再从 parent_id=0 作为根递归 attach，每层按 sort 升序；实现上为避免 `List.of()` 不可变集合排序失败，会先复制到新 `ArrayList` 再排序（见 [CategoryServiceImpl.attach](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/category/service/impl/CategoryServiceImpl.java#L119-L140)）。

### 5.2 ProductManageService（写服务，事务）

- 接口：`create / update / updateStatus / delete`
- 事务边界：在 impl 方法上使用 `@Transactional(rollbackFor = Exception.class)`，确保 SPU / SKUs / Images 原子落库。

**校验顺序（2026-10-09 起，顺序有讲究，别调换）**：

1) `validateSkusNotEmpty`：skus 空 → 2013
2) `validateSkuCodesUnique`：请求内 `sku_code` 不重复 + 查库（`ne(productId, id)` 排除自身）→ 2012
3) `validateIdentitiesUnique`：`(商品名称, 规格)` 唯一性 → **2015**
4) `validateCategoryExists`：分类不存在 → 2001

> **唯一性校验必须排在分类校验之前**：名称 + 规格已存在时这次提交注定失败，
> 先报「分类不存在」会把运营引到无关的错处去改。
>
> `validateIdentitiesUnique` 拦两类：
> ① **本次提交内部**规格重复（同一请求里两行规范化后相同）——`validateSkuCodesUnique`
> 只查 `sku_code`，**拦不住这个**；② **与库中已有记录**重复，查重走
> `ProductMapper.selectOccupiedProductSpecs`（一次 join 取回「名称 → 已占用规格」），
> 修改商品时按**商品粒度**排除本商品自己（不是按 SKU id —— 旧实现会重建 id，按 id 排除会失效）。
> 规格一律过 `SkuSpecNormalizer` 再比。⚠️ 这是**应用层兜底**，不是数据库约束。

**落 SKU：按规范化规格 upsert（2026-10-09 起，替换了原来的「删旧插新」）**

`saveSkus` 现在：查出该商品现有未删除 SKU 并按规范化规格建索引 →
匹配到就 `updateById`（**保留 `sku_id`**）、匹配不到才 `insert` →
请求里没有的逻辑删除（前端契约是「提交即全量」）。

> **为什么必须改掉「先逻辑删除全部 SKU 再重新插入」**（旧实现有三个必然故障）：
> ① 逻辑删除的行仍占着 `uk_sku_code`（该唯一索引**不区分 `is_deleted`**），
> 紧接着插入同一个编码直接撞唯一键 ⇒ **改商品必 500**；
> ② 新 SKU 拿到新自增 id，而 `cart_item.sku_id` / `order_item.sku_id` 仍指向旧 SKU ⇒ 脏引用；
> ③ 库存被当成「新 SKU 的初始值」，前端不传 stock 时静默清零。
>
> **stock 的语义按新增/修改区分**：**新增**时 `stock` 为 `null` → 视为 0；
> **修改**时 `stock` 为 `null` → **保持原值**（`ProductSkuItem.stock` 没有 `@NotNull`，不传是常态）。
> 修改时若传了且与原值不同，经 `InventoryService.adjust(...)` 同步到 `inventory` 表
> （**不直接改表**，否则「改了库存必有账」这条不变量失效）；值没变则跳过，不留无意义的调整流水。
> 注意 `SaveRequest.sku.stock` 因此被解读为**绝对值**。

- create：校验通过后 `productMapper.insert` 拿 productId → `saveSkus(..., isCreate=true)`
  → `saveImages(..., isCreate=true)`。
- update：`saveSkus(..., isCreate=false)` / `saveImages(..., isCreate=false)`，
  最后主动失效详情缓存 `RedisKeys.productDetail(id)`。
- updateStatus：仅更新 product.status，用于上下架；同样失效详情缓存。
- **delete（2026-10-09 新增）**：`PRODUCT_NOT_FOUND`（2010）→ 校验 `status = 0`（否则 **2016**）
  → 连带**逻辑删除**其全部 SKU 与图片（残留 SKU 的 `sku_code` 仍占着全局唯一编码）
  → 逻辑删除商品 → 失效详情缓存。
  > 删除被移除的 SKU / 整个商品前**不检查购物车引用**（2026-10-09 决策）：
  > `cart_item` 引用已删 SKU 时购物车会标记为「商品已失效」，这个状态前台本来就要处理；
  > 加拦截反而会让运营「只想改价」时被卡住，且没有强制通道。

### 5.3 ProductQueryService（读服务，聚合）

- 接口：`adminPage / adminDetail / appPage / appDetail`。
- appPage：强制覆盖 status = 1，保证前台只看得到上架商品。
- appDetail：先 selectById，不存在 2010；status !=1 → 2014。
- 列表/详情聚合点：
  - 「分类名」：批量 `categoryMapper.selectBatchIds(ids)` → 组装成 map；对单条详情 `categoryMapper.selectById`。
  - 「最低价 minPrice」：批量查询该页/该商品的 SKU，用 HashMap 按 productId 聚合 min(price)。
  - 「SKU 顺序」：ProductDetailVO 的 skus 按 price 升序，便于前台默认展示最低价 SKU。
  - 「图片顺序」：按 `sort` 升序 orderByAsc 查询返回。

### 5.4 ProductImportService（批量导入，异步）

接口：`submitImport(MultipartFile, Long adminId)` / `buildTemplate()`
实现：[ProductImportServiceImpl](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductImportServiceImpl.java)
依赖：`spring-shop-common` 的 `ExcelSupport`（Fesod 薄封装）与 `excel.task` 任务框架，
product 模块本身**不直接引 Fesod / POI**。

**受理与执行分离**：`submitImport` 只做「校验文件格式 → 交给 `ExcelTaskExecutor`」，
立刻返回 `ExcelTaskVO`（含 `taskNo`）；真正解析落库跑在独立的 `excel-task-*` 线程池里，
前端拿 `taskNo` 轮询 `GET /api/admin/excel-tasks/{taskNo}` 看进度，失败明细从
`GET /api/admin/excel-tasks/{taskNo}/download` 下载。

这样做的理由是**上万行**：同步请求里跑几分钟的导入必然超时，且连接被占死；
而上传文件必须先落盘（`ExcelFileStorage`）——HTTP 请求一返回，`MultipartFile` 依赖的临时文件
就被容器回收了，后台线程再读就是空流。

**模板结构：一行一个 SKU。** 商品级字段（名称 / 副标题 / 主图 / 分类）在同一个商品的多行里重复填写，
解析时按「商品名称」聚合（`LinkedHashMap` 保序），同名多行合并成 **一个 SPU + 多个 SKU**。
这样单 SKU 和多规格商品都能用同一份模板表达，不需要嵌套结构。

10 列（顺序与代码里的列下标常量一一对应）：

```
商品名称* | 副标题 | 主图URL | 分类名称* | SKU编码* | 规格 | 销售价* | 原价 | 库存 | 状态(1上架/0下架)
```

**唯一性：按 `(商品名称, 规格)` 逐行判定（2026-10-09 起，替换了原来的「按名称整组拒绝」）**：

| 情形 | 行为 |
|---|---|
| 名称在库里不存在 | 新建 SPU，本组所有合法行作为它的 SKU |
| 名称已存在 + **规格不同** | **复用现有 `productId`，只追加 SKU，不新建 SPU** |
| 名称已存在 + **规格相同** | 只拒绝**这一行**，同组其它规格的行照常导入 |

> 原来的「按名称整组拒绝」是为了防止「同名 + 换一批 SKU 编码」的重传静默建出第二个同名 SPU
> （`product.name` 上没有唯一索引）。但它把「给已有商品补一个规格」这个完全正当的诉求
> 也一并堵死了。改成按 `(名称, 规格)` 逐行判定后，真正的重复仍被拦住，合法的追加得以放行。
>
> **复用 SPU 时商品级字段（分类 / 副标题 / 主图）一律忽略**：这三列描述的是 SPU，
> 而本次只往既有 SPU 追加 SKU。所以复用分支既不解析分类、也不写这三个字段 ——
> 顺手写入等于「悄悄改了别人的商品」，比忽略更危险。
>
> 查重 SQL `ProductMapper.selectOccupiedProductSpecs` 用的是 **LEFT JOIN**：库里存在
> 「SKU 全被逻辑删除、商品本身还在」的行，用 INNER JOIN 会让它在结果里消失，
> 导入于是给它再建一个同名 SPU（正是规则要消灭的现象）。LEFT JOIN 让这类商品以
> `specs = null` 出现，消费方据此既能拿到 `productId`（复用），
> 又不会把「没有 SKU」误判成「占用了一个空规格」。

**校验分两级**，顺序不能颠倒（组级里的分类 / 长度校验**只在新建 SPU 时执行**）：

1. **组级（整个商品）**：商品名非空且 ≤100；分类名称能**唯一**解析到分类 id。
   - 分类不存在、或存在多个同名分类 → 该商品**所有行**都记为失败（`groupError` 逐行铺开）。
     因为缺分类的商品没有任何一行能落库，只报一行会让运营以为改一行就行。
   - 副标题 ≤200、主图 URL ≤255。
2. **行级（单个 SKU）**：SKU 编码非空且 ≤64、规格 ≤255、销售价必填且 >0、原价非负、
   库存非负（缺省 0）、状态只能是 0/1（缺省 1）。
   - 价格额外做精度校验：`DECIMAL(10,2)` 会**静默四舍五入**，所以超过 8 位整数或 2 位小数直接拒绝，
     而不是让数据库悄悄改数。

**三个容易踩的顺序问题**（都已在实现中处理，改代码时别改回去）：

- **SKU 编码要在所有字段校验通过后才标记占用**。否则「因价格写错而失败的行」会把编码锁死，
  同一份文件里后面那个编码正确、价格正确的行反而被误报为重复。
- **先构建完所有 SKU，再插入 SPU**。否则会出现「SPU 已创建但一个 SKU 都没有」的空商品。
- **收尾必须无条件上报进度**。只在「有分组」时上报，会让一个「所有行都因商品名为空而失败」的文件
  以 `处理 0 行 / 失败 0 行` 收场，而失败明细里明明有内容。

**重复校验要与索引口径一致**：
`product_sku.sku_code` 上的 `uk_sku_code` 唯一索引**不排除逻辑删除的行**，
所以导入前的占用查询用 `ProductSkuMapper.selectOccupiedSkuCodes`（裸 SQL，不带 `is_deleted` 条件），
而不是用 MyBatis-Plus 的 lambda 查询——否则逻辑删除过的 SKU 编码会被判为可用，
最终在 insert 阶段撞唯一键抛 500。编码按 1000 个一片分片查询，避免单条 `IN (...)` 超出报文/占位符上限。

**部分成功语义**：合法行照常入库，非法行逐行记进 `excel_task_error`。
`rowNum` 用的是**用户在 Excel 里看到的行号**（表头是第 1 行，数据从第 2 行起），报错可直接对着表格定位。
行级失败**不改变任务的成功状态**（任务 `status` 只回答「跑完了没有、有没有整体失败」），
失败信息由 `successRows / failRows` 两个计数 + 可下载的失败明细表达。

**事务与并发**：执行体不用 `@Transactional`（跑在异步线程里，自调用不走代理），
而是用 `TransactionTemplate` 做「批内原子、批间独立」（批大小 `excel.task.import-batch-size`）。
整批失败时**降级为逐组重试**，把唯一键冲突精确落到那一组，其余组照常导入成功。

**内存特性**：读走 Fesod 的 SAX 事件流（不构建整表 DOM），但**分组需要跨行聚合**，
所以数据行会驻留内存（上限 `excel.task.max-import-rows`，默认 10 万行）。
十万行 `ExcelRow` 的堆占用约几十 MB，是「按名称聚合」这个语义换来的必要代价。

**行数上限**：`excel.task.max-import-rows`（默认 100000）。超限直接报错而不是截断，
避免运营以为全导进去了。

### 5.5 ProductExportService（批量导出，异步）

接口：`submitExport(ProductExportQuery, Long adminId)`
实现：[ProductExportServiceImpl](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductExportServiceImpl.java)

与导入对称：`POST /api/admin/products/export` 受理后立刻返回 `taskNo`，后台线程**边查边写**——
按 `excel.task.export-page-size`（默认 5000）分页拉取，每页转成
[ProductExportRow](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/vo/ProductExportRow.java)
后立刻写进 Excel 写缓存。全程只有「一页数据 + Fesod 百行写缓存」在内存里，
十万行导出与一千行导出的内存占用基本相同。

分页取数用 `ProductQueryService.adminExportPage`（复用列表页的筛选口径，保证「看到的」=「导出的」），
分页对象用 `new Page<>(current, pageSize, false)`——`searchCount=false` 省掉每页一次 COUNT，
导出并不展示总页数。

`ids` 优先于其它条件：用户勾了行就是明确的意图（「导出选中」）。

## 六、控制器与权限

- 后台统一前缀 `/api/admin/**`，被 `SecurityConfig.adminSecurityFilterChain` 的 `adminPrincipalAuthorizationManager` 严格校验必须是 `AdminUserPrincipal`（见 [SecurityConfig.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/java/com/springshop/web/security/SecurityConfig.java)）。
- 控制器方法级按钮权限：
  - 分类：`product:category:{list|create|update|delete}`（由 [AdminCategoryController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminCategoryController.java) 的 `@PreAuthorize` 生效）。
  - 商品：`product:product:{list|create|update|delete}`，导入另用 `product:product:import`（由 [AdminProductController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java) 的 `@PreAuthorize` 生效）。
    - `DELETE /api/admin/products/{id}`（2026-10-09 新增）→ 权限 `product:product:delete`，**仅下架商品可删**（否则 2016），逻辑删除并连带 SKU / 图片。
    - 新权限码必须在 `AdminDataInitializer` 里幂等注册菜单，否则
      `AdminPermissionCoverageIntegrationTest` 会反射比对失败（CI 红）。
- 商品导入 / 导出接口：
  - `POST /api/admin/products/import`（`multipart/form-data`，字段名 `file`）→ 权限 `product:product:import`，
    返回 `Result<ExcelTaskVO>`（异步受理）
  - `POST /api/admin/products/export`（JSON body，可选 `ids`）→ 权限 `product:product:list`，
    返回 `Result<ExcelTaskVO>`（异步受理）
  - `GET /api/admin/products/import/template` → 权限 `product:product:import`，返回 xlsx 附件
  - 文件大小上限由 `spring.servlet.multipart.max-file-size`（5MB）控制，超限由
    `GlobalExceptionHandler.handleMaxUploadSizeExceeded` 转成可读提示「上传文件过大，请拆分后分批导入」。
  - ⚠️ **但这个提示能送达有个前提**：必须同时配置 `server.tomcat.max-swallow-size`（当前 12MB）。
    Tomcat 的 `maxSwallowSize` 默认只有 2MB —— 被拒绝的请求体一旦超过 2MB，Tomcat 会直接断连，
    客户端只看到 `Broken pipe` / `chunked transfer encoding` 之类的 I/O 错误，
    **友好提示根本没机会返回**。这一点由
    [ProductImportMultipartIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportMultipartIntegrationTest.java)
    用真实 Tomcat 守住（MockMvc 覆盖不到，见该测试类注释）。
  - **导出不新增权限码**：沿用列表的 `product:product:list`。「能看列表就能导出列表」是同一份数据的
    同一个权限，另造一个 `product:product:export` 只会让菜单种子数据与已有角色授权多一层维护成本。
- 前台公开接口（SecurityConfig 白名单 permitAll）：
  - `GET /api/categories/tree` → [AppCategoryController.tree](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/app/AppCategoryController.java)
  - `GET /api/products`、`GET /api/products/{id}` → [AppProductController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/app/AppProductController.java)

### 关于操作审计注解的取舍

MVP 阶段未在 product 后台接口直接使用 admin 模块的 `@OperationLog` 注解。根本原因：`@OperationLog` 与其切面在 `spring-shop-admin` 内部，强依赖 admin 的 entity/mapper/principal；若 product 直接 import admin 包将导致依赖倒置。

当前取舍：

- RBAC 按钮级权限仍然通过 product 模块自带的 `spring-security-core` 的 `@PreAuthorize` 保证生效；
- 后续可在 admin 模块扩展 AOP 切点，对路径匹配 `/api/admin/**` 的控制器调用统一写入操作日志（此时 product 无需任何改动），保持单向依赖。

## 七、菜单初始化

[AdminDataInitializer](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java) 注入以下菜单与按钮权限：

- 商品管理（目录）
  - 分类管理（菜单）
    - 新增/修改/删除分类（按钮权限，权限标识 `product:category:create/update/delete`）
  - 商品管理（菜单）
    - 新增/修改商品（按钮权限 `product:product:create/update`）
    - 导入商品（按钮权限 `product:product:import`）

> 注意：初始化器已改为**逐项 find-or-create**（以 `menu.permission_code` 为幂等键），
> 所以新增权限标识后重启应用即可自动写入老库，不需要手工插库，也不会重复插入。
> 早先「表非空就整体跳过」的写法会导致新权限永远进不了已有库，接口稳定 403。

MVP 首次启动时由 data initializer 将上述菜单写入 `menu` 表，配合 `product:category:list` / `product:product:list` 的菜单级权限被 Spring Security 的 `@PreAuthorize` 校验。

## 八、测试基座

### 8.1 单元测试

全部使用 JUnit5 + Mockito，运行无需 MySQL/Redis。

- [CategoryServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/category/service/impl/CategoryServiceImplTest.java)（9 用例）：create/updateNotFound/deleteHasChildren/deleteHasProducts/getById/tree，以及**分类树缓存**（命中不查库、miss 回源并写入、增删改后失效）。打桩小技巧：`selectCount(Wrappers.<ProductCategory>lambdaQuery().eq(...))` 的严格匹配会触发 Mockito `PotentialStubbingProblem`，替换为 `any()` 即可。
- [ProductManageServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductManageServiceImplTest.java)（17 用例）：SKU 空、分类不存在、SKU 编码重复、创建成功、上下架不存在，**写后失效商品详情缓存**（`verify(stringRedisTemplate).delete(RedisKeys.productDetail(id))`），以及 2026-10-09 新增的 **唯一性 + 删除 + Bug A/B/C**：
  - `(名称, 规格)` 撞库 → 2015；**同一次提交内**规格重复（全角冒号）→ 2015；修改时撞别的商品 → 2015；修改时**排除自己**（按商品粒度）不误报。
  - 修改时**就地更新并保留 `sku_id`**、不再「先删全部」、库存未变时不留调整流水（Bug A/C）；
    不传 `stock` 时**保持原值**（Bug B）。
  - 删除：商品不存在 → 2010；**上架不可删** → 2016；下架可删且**连带删 SKU / 图片 + 失效缓存**。
  - ⚠️ 顺序坑：`validateSkuCodesUnique` 现在排在 `validateCategoryExists` 之前，所以「SKU 编码重复」用例
    **不能再桩 `categoryMapper`**（否则是 UnnecessaryStubbing）。
- [SkuSpecNormalizerTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/support/SkuSpecNormalizerTest.java)（9 用例）：规格规范化纯函数 —— 空/空白/纯分隔符都归成同一个空键、分隔符变体、段内空白、全角冒号、**段排序**、空段丢弃、反向区分（`黑` vs `黑;尺寸:L`、`Size:A` vs `Size:a`）、幂等。
- [ProductQueryServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductQueryServiceImplTest.java)（9 用例）：adminDetail 不存在 / appDetail 不存在 / appDetail 下架 / adminDetail 聚合 / adminPage 分页+分类名+最低价，以及**商品详情缓存**（命中直接返回、miss 回源写缓存、商品不存在时写 `NULL` 占位符并给短 TTL 防穿透）。
  - 单测预热 MyBatis-Plus Lambda cache：`@BeforeAll warmupMybatisPlusLambdaCache()` 手动 `TableInfoHelper.initTableInfo`，否则 `LambdaQueryWrapper` 在走 Mockito 打桩前因无 lambda 元数据抛异常。
  - 存在一些共享桩，类上使用 `@MockitoSettings(strictness = Strictness.LENIENT)` 规避 UnnecessaryStubbingException。
- [ProductImportServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductImportServiceImplTest.java)（22 用例）：同名多行聚合为一个 SPU、分类不存在整组失败、SKU 编码重复、价格精度、状态非法、空文件 / 非 Excel 后缀、模板可解析等。
  - **2026-10-09 按新语义改写的部分**：`(名称, 规格)` 逐行判定 ——
    名称已存在 + 规格不同 → **复用 `productId` 追加 SKU**（`verify(productMapper, never()).insertBatch(any())`）；
    名称已存在 + 规格相同 → 只拒那一行；规格规范化（段序 / 全角冒号）视为重复；文件内同名同规格第二行被拒；
    「复用分支忽略商品级字段（分类填了不存在的名字也不失败）」；
    「LEFT JOIN 返回 `specs = null`（SKU 全被逻辑删除的同名商品）时应复用而不是再建一个 SPU」。
  - ⚠️ 桩的坑：`stubInsertAssignsId()` 里的 `productMapper.insertBatch` 必须 `lenient()` ——
    复用分支本来就不该建 SPU，严格模式下会被判为无用桩；而「全部行都被拒」的用例**干脆不要调它**。
  - 这个测试**真的生成 xlsx**：用 `ExcelSupport.write(...)` 造字节数组，再包成 `MockMultipartFile` 喂给 service，
    比手写 `InputStream` 更贴近真实链路，也顺带覆盖了读写两个方向。
  - 同样需要 `@BeforeAll warmupMybatisPlusLambdaCache()`（实体：`Product` / `ProductSku` / `ProductCategory`）。

### 8.2 集成测试

[ProductIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java)：走完整 Spring 容器，使用 H2 内存库（MySQL 兼容模式）+ Flyway `db/migration-test` 脚本。当前覆盖：

- `admin_category_tree_should_require_login`：未登录访问 `/api/admin/categories/tree` → 401
- `app_category_tree_should_public`：匿名访问 `/api/categories/tree` → 200 + Result.code = 200
- `admin_product_page_should_require_login`：未登录访问 `/api/admin/products` → 401
- `app_product_page_should_public`：匿名访问 `/api/products?current=1&size=5` → 200 + Result.code = 200

[ProductImportIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportIntegrationTest.java)（9 用例）：覆盖「管理员登录 → 建分类 → 上传 Excel → 落库 → 后台列表可见」全链路。

- 未带 token 导入 → 401
- 同名两行（**规格不同**）→ 一个 SPU + 两个 SKU
- 导入后通过 `GET /api/admin/products?keyword=` 能查到
- 分类不存在 → 该商品所有行失败，`failRows=2`
- SKU 编码与库中已有重复 → 只失败那一行，其余行照常入库
- **同名同规格重传 → 拒绝该行，且库里仍只有一个同名商品**（不再整组拒绝，但也不会建出第二个 SPU）
- **同名不同规格重传 → 追加 SKU 到同一个 SPU**（库里仍只有一个同名商品）
- 非 Excel 文件 → code = 2020
- 模板下载 → 能重新解析，且示例行是两行同名商品

[ProductUpdateIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductUpdateIntegrationTest.java)（12 用例）：
**专为 `PUT /api/admin/products/{id}` 而建** —— 此前**没有任何用例打过这个接口**，
而写服务单测是纯 Mockito（mapper 被 mock），于是 Bug A/B/C 在测试里**结构性不可见**、一路漏到线上。

- 沿用同一 `sku_code` 修改必须**成功且保留原 `sku_id`**（Bug A + C）
- 请求不带 `stock` → `inventory.stock` **保持原值**；带了且不同 → 同步到 `inventory` 表（Bug B）
- 请求里没有的 SKU 被移除
- `(名称, 规格)` 唯一性：同规格拒绝 2015、同名不同规格放行、全角冒号视为重复、
  同一次提交内重复拒绝、改名撞别的商品拒绝、**修改时不把自己当成冲突**
- 删除：上架被拒 2016 → 下架后删除成功且从后台列表消失；未知商品 2010
- 断言一律走真实 HTTP + 真实库（库存断言查 `inventory` 表；`product_sku` 上已无库存列）；
  分类在本类内自建，带 `@Transactional` 回滚，不污染共享 H2。

> 这两个新测试类同样必须带齐 4 项注解（`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`）
> 和 `@MockBean StringRedisTemplate`，否则在 CI 上会因缺 Redis 或数据串场而挂。

[ProductImportMultipartIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportMultipartIntegrationTest.java)（6 用例）：
**唯一一个用真实 Tomcat 的集成测试**，`webEnvironment = RANDOM_PORT` + `TestRestTemplate`。

- 存在的理由：MockMvc 的 `multipart()` 是**伪造**请求（构造 `MockMultipartHttpServletRequest`），
  直接跳过 Servlet 容器的 multipart 解析，因此「真实文件上传」和「容器级大小限制」两条路径完全没被覆盖。
- 覆盖：模板下载的真实响应头 + 内容可被 POI 解析；真实 multipart 上传 xlsx 能被解析出正确行数；
  **成功路径**（一个 SPU + 两个 SKU 落库并可从后台列表读回）；非 Excel 文件返回 2020；
  **超限文件返回可读提示而非断连**（这条正是它抓出 `max-swallow-size` 问题的用例）。
- 两个刻意的写法：
  1. **不加 `@Transactional`** —— 请求跑在 Tomcat 工作线程里，测试方法的事务回滚不了服务端事务，
     加了只会制造「已经回滚」的错觉。除成功路径那一个用例外，其余只做只读或必然失败的操作；
     成功路径使用**全局唯一**的分类名 / 商品名，且不依赖「总数」断言，避免污染共享的
     `jdbc:h2:mem:spring_shop_test`（同一 JVM 内是共享的）。
  2. 上传用的 `ByteArrayResource` 必须**覆写 `getFilename()`**，否则 multipart 会退化成普通字段，
     服务端不会按「上传文件」处理。

可继续扩展的集成测试方向（后续 TODO）：

- 前台商品详情访问下架 SPU 断言 code = 2014；
- SKU 编码重复跨商品的创建失败场景；
- 导入超过 `excel.task.max-import-rows`（默认 10 万行）断言被拒绝；
- 导出期间并发改数据，验证 OFFSET 分页是否出现重复/漏行（见「九、后续迭代建议」第 9 条）。

### 8.3 运行命令

```bash
# 仅跑 product 模块单测（注意带 -am，且兄弟模块没匹配测试时不失败）
mvn -pl spring-shop-product -am test -Dsurefire.failIfNoSpecifiedTests=false

# 仅跑商品集成测试
mvn -pl spring-shop-web -am test -Dtest=ProductIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

# 全量回归（H2+Flyway，无需本地 MySQL/Redis）
mvn clean test
```

## 九、后续迭代建议

1. **操作日志**：在 admin 模块扩展 AOP 切点，对路径匹配 `/api/admin/**` 的 controller 调用统一记录（解决 product 不引用 admin 的分层约束）。目前 product / order 的后台写接口**没有**审计日志，只有 admin 模块自己的接口带 `@OperationLog`。
2. **写服务性能**：当前 SKU/Image 更新是「先 delete 再重插」，后续可改为按 id 做真正增量 upsert，减少 delete+insert 对索引/自增 ID 的冲击；大量 SKU 时可走批量 insert（导入路径同样逐条 insert，行数上限内可接受）。
3. ~~**读服务缓存**~~：✅ **已落地**。分类树走 `RedisKeys.categoryTree()`（`category:tree`，TTL 1 小时，增删改后 DEL）；商品详情走 `RedisKeys.productDetail(id)`（`product:detail:{id}`，TTL 30 分钟 + 0~5 分钟随机抖动避免集中过期；商品不存在时写 `"NULL"` 占位符 + 60 秒短 TTL 防穿透；上下架/改价后 DEL）。**仍未做**的是商品列表页缓存（`product:page:{...}`）—— 筛选组合多、失效面大，收益暂不明确。
4. **sku_code 唯一校验的并发窗口**：当前 DB 已建 `uk_sku_code`，业务代码仅做前置提示（导入路径已按索引口径预查，见 5.4）。若并发创建不同商品但 sku_code 相同，最终会由数据库唯一约束抛 DataIntegrityViolationException，后续可在 `GlobalExceptionHandler` 统一转成 2012。
5. **SKU 启用/停用**：`ProductSku.status` 已落库，当前查询未区分 status。MVP 按「全部返回」简化；后续 minPrice、前台展示仅统计 status=1 的 SKU。
6. **富文本安全**：Product.detail 是 HTML 富文本，后续上线前台展示前增加 XSS 过滤（Spring Security headers + 内容清洗库）。
7. **查询条件扩展**：关键词模糊目前仅对 `name like`；后续可扩 `subtitle`、`category_id 递归子分类`、品牌等更贴近电商实际搜索。
8. ~~**导入异步化**~~：✅ **已落地**。导入/导出已改为「上传 → 异步任务 → 结果下载」（`ExcelTaskExecutor` + `excel-task` 线程池），行数上限提升到 `excel.task.max-import-rows`（默认 10 万行），部分成功的结果改为可下载的失败明细文件。详见 5.4 / 5.5。
9. **导出分页仍是 OFFSET**：`ExcelExportSupport` 用 `Page<>(current, pageSize, false)` 逐页取数，十万行导出期间若有并发插入/删除，结果集会漂移（重复或漏行）。建议改 keyset 分页（`WHERE id < lastId ORDER BY id DESC LIMIT n`）。
10. **导入表头不校验**：列位置按下标常量（`COL_XXX = 0/1/2...`）取值，用户把两列对调后导入仍会「成功」，只是数据串列。缺少模板版本号与表头内容比对。

## 十、关键修改文件一览（按 task commit 顺序）

参见父 POM、各模块 POM、ResultCode、AdminDataInitializer、SecurityConfig 等关键改动的真实文件：

- [父 pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/pom.xml#L21-L27)（注册 product 模块）
- [spring-shop-product/pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/pom.xml)（含 spring-security-core）
- [spring-shop-web/pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/pom.xml#L26-L33)（依赖 product）
- [ResultCode.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java#L21-L29)（2000 段商品错误码）
- [SecurityConfig.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/java/com/springshop/web/security/SecurityConfig.java#L88-L94)（前台公开路径放行）
- [AdminDataInitializer.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java)（商品管理菜单与按钮权限初始化）
- [CategoryServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/category/service/impl/CategoryServiceImpl.java)
- [ProductManageServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductManageServiceImpl.java)
- [ProductQueryServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductQueryServiceImpl.java)
- [ProductIntegrationTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java)

### 批量导入相关（后加）

- [ExcelSupport.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ExcelSupport.java) / [ExcelRow.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ExcelRow.java) / [ImportError.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ImportError.java) / [ExcelStreamWriter.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ExcelStreamWriter.java) / [ExcelExportSupport.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ExcelExportSupport.java)（**Fesod** 薄封装，公共模块；不再使用 POI）
- [ProductImportServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductImportServiceImpl.java) / [ProductExportServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductExportServiceImpl.java) / [ProductExportRow.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/vo/ProductExportRow.java)
- [ProductSkuMapper.selectOccupiedSkuCodes](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductSkuMapper.java)（与唯一索引口径一致的占用查询）
- [AdminProductController.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java)（`/import` 与 `/import/template`）
- [ProductImportServiceImplTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductImportServiceImplTest.java)
- [ProductImportIntegrationTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportIntegrationTest.java)
