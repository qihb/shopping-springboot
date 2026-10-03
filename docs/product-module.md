# 商品模块（spring-shop-product）技术梳理

本文档梳理 `spring-shop-product` 模块的 MVP 实现，包含模块职责、分层、数据模型、关键服务、接口、测试基座与后续可扩展点。

## 一、模块职责与边界

**模块名**：`spring-shop-product`，隶属 spring-shop 单体业务模块之一。

- **上游依赖**：`spring-shop-common`（统一响应、JSR303、MyBatis-Plus、JWT/安全注解）
- **下游被依赖**：`spring-shop-web`（启动模块），`spring-shop-web` 负责把 product 模块的控制器扫入 Spring 容器并加载 Mapper。
- **禁止依赖**：`spring-shop-admin`、`spring-shop-user`（保持模块单向依赖 `web → product → common`，避免环）
- **当前范围（MVP）**：
  - 分类子域：后台 CRUD + 前后台分类树
  - 商品子域：SPU/SKU/Image 写服务 + 读服务聚合 + 前后台列表/详情接口

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

- **product_category**：parent_id=0 表示根，sort 越小越靠前；逻辑删除 + 乐观锁 version。
- **product**（SPU）：价格与库存不落此表，主图、详情、销量、上下架状态 status=1 上架。
- **product_sku**：价格/库存唯一事实源；`sku_code` 数据库唯一约束 `uk_sku_code`。
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

- 接口：`create / update / updateStatus`
- 事务边界：在 impl 方法上使用 `@Transactional(rollbackFor = Exception.class)`，确保 SPU / SKUs / Images 原子落库。

- create 流程：
  1) `validateSkusNotEmpty`：skus 空 → 2013
  2) `validateCategoryExists`：category 不存在 → 2001
  3) `validateSkuCodesUnique`：先校验请求内 skuCode 不重复，再用 DB 的 `selectCount(...eq(skuCode).ne(productId, id))` 查全表唯一性，冲突 → 2012
  4) productMapper.insert 拿到 productId（插入前在 MP 里 setId，测试里打桩手动 `p.setId(100L)`）
  5) 遍历 ProductSkuItem 逐条 `productSkuMapper.insert`（MVP 采取「逐条」+ 增量保存：更新时先 delete 再重插，简单可靠）
  6) ProductImageItem 同理，未填 sort 时按索引顺序 idx 写入。

- update：整体走「先删 SKU/Image，再重新插入」的增量合并策略；更新时 `ne(productId, id)` 跳过自身 SKU。
- updateStatus：仅更新 product.status，用于上下架（与批量/细化场景后续可再扩展）。

### 5.3 ProductQueryService（读服务，聚合）

- 接口：`adminPage / adminDetail / appPage / appDetail`。
- appPage：强制覆盖 status = 1，保证前台只看得到上架商品。
- appDetail：先 selectById，不存在 2010；status !=1 → 2014。
- 列表/详情聚合点：
  - 「分类名」：批量 `categoryMapper.selectBatchIds(ids)` → 组装成 map；对单条详情 `categoryMapper.selectById`。
  - 「最低价 minPrice」：批量查询该页/该商品的 SKU，用 HashMap 按 productId 聚合 min(price)。
  - 「SKU 顺序」：ProductDetailVO 的 skus 按 price 升序，便于前台默认展示最低价 SKU。
  - 「图片顺序」：按 `sort` 升序 orderByAsc 查询返回。

### 5.4 ProductImportService（批量导入，事务）

接口：`importProducts(MultipartFile)` / `buildTemplate()`
实现：[ProductImportServiceImpl](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductImportServiceImpl.java)
依赖：`spring-shop-common` 的 `ExcelSupport`（POI 薄封装），product 模块本身不直接引 POI。

**模板结构：一行一个 SKU。** 商品级字段（名称 / 副标题 / 主图 / 分类）在同一个商品的多行里重复填写，
解析时按「商品名称」聚合（`LinkedHashMap` 保序），同名多行合并成 **一个 SPU + 多个 SKU**。
这样单 SKU 和多规格商品都能用同一份模板表达，不需要嵌套结构。

10 列（顺序与代码里的列下标常量一一对应）：

```
商品名称* | 副标题 | 主图URL | 分类名称* | SKU编码* | 规格 | 销售价* | 原价 | 库存 | 状态(1上架/0下架)
```

**校验分两级**，顺序不能颠倒：

1. **组级（整个商品）**：商品名非空且 ≤100；分类名称能**唯一**解析到分类 id。
   - 分类不存在、或存在多个同名分类 → 该商品**所有行**都记为失败（`addGroupError` 逐行铺开）。
     因为缺分类的商品没有任何一行能落库，只报一行会让运营以为改一行就行。
   - 副标题 ≤200、主图 URL ≤255。
2. **行级（单个 SKU）**：SKU 编码非空且 ≤64、规格 ≤255、销售价必填且 >0、原价非负、
   库存非负（缺省 0）、状态只能是 0/1（缺省 1）。
   - 价格额外做精度校验：`DECIMAL(10,2)` 会**静默四舍五入**，所以超过 8 位整数或 2 位小数直接拒绝，
     而不是让数据库悄悄改数。

**两个容易踩的顺序问题**（都已在实现中处理，改代码时别改回去）：

- **SKU 编码要在所有字段校验通过后才标记占用**。否则「因价格写错而失败的行」会把编码锁死，
  同一份文件里后面那个编码正确、价格正确的行反而被误报为重复。
- **先构建完所有 SKU，再插入 SPU**。否则会出现「SPU 已创建但一个 SKU 都没有」的空商品。

**重复校验要与索引口径一致**：
`product_sku.sku_code` 上的 `uk_sku_code` 唯一索引**不排除逻辑删除的行**，
所以导入前的占用查询用 `ProductSkuMapper.selectOccupiedSkuCodes`（裸 SQL，不带 `is_deleted` 条件），
而不是用 MyBatis-Plus 的 lambda 查询——否则逻辑删除过的 SKU 编码会被判为可用，
最终在 insert 阶段撞唯一键抛 500。

**部分成功语义**：合法行照常入库，非法行逐行返回 `{rowNum, message}`。
`rowNum` 用的是**用户在 Excel 里看到的行号**（表头是第 1 行，数据从第 2 行起），报错可直接对着表格定位。

返回体 `ProductImportResultVO`：`totalRows / productCount / skuCount / failRowCount / errors`。

**行数上限 1000**（`ExcelSupport.MAX_ROWS`）。超限直接报错而不是截断，避免运营以为全导进去了。

## 六、控制器与权限

- 后台统一前缀 `/api/admin/**`，被 `SecurityConfig.adminSecurityFilterChain` 的 `adminPrincipalAuthorizationManager` 严格校验必须是 `AdminUserPrincipal`（见 [SecurityConfig.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/java/com/springshop/web/security/SecurityConfig.java)）。
- 控制器方法级按钮权限：
  - 分类：`product:category:{list|create|update|delete}`（由 [AdminCategoryController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminCategoryController.java) 的 `@PreAuthorize` 生效）。
  - 商品：`product:product:{list|create|update}`，导入另用 `product:product:import`（由 [AdminProductController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java) 的 `@PreAuthorize` 生效）。
- 商品导入接口：
  - `POST /api/admin/products/import`（`multipart/form-data`，字段名 `file`）→ 权限 `product:product:import`
  - `GET /api/admin/products/import/template` → 权限 `product:product:import`，返回 xlsx 附件
  - 文件大小上限由 `spring.servlet.multipart.max-file-size`（5MB）控制，超限由
    `GlobalExceptionHandler.handleMaxUploadSizeExceeded` 转成可读提示「上传文件过大，请拆分后分批导入」。
  - ⚠️ **但这个提示能送达有个前提**：必须同时配置 `server.tomcat.max-swallow-size`（当前 12MB）。
    Tomcat 的 `maxSwallowSize` 默认只有 2MB —— 被拒绝的请求体一旦超过 2MB，Tomcat 会直接断连，
    客户端只看到 `Broken pipe` / `chunked transfer encoding` 之类的 I/O 错误，
    **友好提示根本没机会返回**。这一点由
    [ProductImportMultipartIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportMultipartIntegrationTest.java)
    用真实 Tomcat 守住（MockMvc 覆盖不到，见该测试类注释）。
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

MVP 首次启动时由 data initializer 将上述菜单写入 `sys_menu`，配合 `product:category:list` / `product:product:list` 的菜单级权限被 Spring Security 的 `@PreAuthorize` 校验。

## 八、测试基座

### 8.1 单元测试

全部使用 JUnit5 + Mockito，运行无需 MySQL/Redis。

- [CategoryServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/category/service/impl/CategoryServiceImplTest.java)（5 用例）：create/updateNotFound/deleteHasChildren/getById/tree。打桩小技巧：`selectCount(Wrappers.<ProductCategory>lambdaQuery().eq(...))` 的严格匹配会触发 Mockito `PotentialStubbingProblem`，替换为 `any()` 即可。
- [ProductManageServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductManageServiceImplTest.java)（5 用例）：SKU 空、分类不存在、SKU 编码重复、创建成功、上下架不存在。
- [ProductQueryServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductQueryServiceImplTest.java)（5 用例）：adminDetail 不存在 / appDetail 不存在 / appDetail 下架 / adminDetail 聚合 / adminPage 分页+分类名+最低价。
  - 单测预热 MyBatis-Plus Lambda cache：`@BeforeAll warmupMybatisPlusLambdaCache()` 手动 `TableInfoHelper.initTableInfo`，否则 `LambdaQueryWrapper` 在走 Mockito 打桩前因无 lambda 元数据抛异常。
  - 存在一些共享桩，类上使用 `@MockitoSettings(strictness = Strictness.LENIENT)` 规避 UnnecessaryStubbingException。
- [ProductImportServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductImportServiceImplTest.java)（11 用例）：同名多行聚合为一个 SPU、分类不存在整组失败、同名分类歧义、SKU 编码重复、价格精度、状态非法、空文件 / 非 Excel 后缀、模板可解析等。
  - 这个测试**真的生成 xlsx**：用 `ExcelSupport.write(...)` 造字节数组，再包成 `MockMultipartFile` 喂给 service，
    比手写 `InputStream` 更贴近真实链路，也顺带覆盖了读写两个方向。
  - 同样需要 `@BeforeAll warmupMybatisPlusLambdaCache()`（实体：`Product` / `ProductSku` / `ProductCategory`）。

### 8.2 集成测试

[ProductIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java)：走完整 Spring 容器，使用 H2 内存库（MySQL 兼容模式）+ Flyway `db/migration-test` 脚本。当前覆盖：

- `admin_category_tree_should_require_login`：未登录访问 `/api/admin/categories/tree` → 401
- `app_category_tree_should_public`：匿名访问 `/api/categories/tree` → 200 + Result.code = 200
- `admin_product_page_should_require_login`：未登录访问 `/api/admin/products` → 401
- `app_product_page_should_public`：匿名访问 `/api/products?current=1&size=5` → 200 + Result.code = 200

[ProductImportIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportIntegrationTest.java)（7 用例）：覆盖「管理员登录 → 建分类 → 上传 Excel → 落库 → 后台列表可见」全链路。

- 未带 token 导入 → 401
- 同名两行 → 一个 SPU + 两个 SKU（断言 `productCount=1 / skuCount=2`）
- 导入后通过 `GET /api/admin/products?keyword=` 能查到
- 分类不存在 → 该商品所有行失败，`failRowCount=2`
- SKU 编码与库中已有重复 → 只失败那一行，其余行照常入库
- 非 Excel 文件 → code = 2020
- 模板下载 → 能重新解析，且示例行是两行同名商品

> 这两个新测试类同样必须带齐 4 项注解（`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`）
> 和 `@MockBean StringRedisTemplate`，否则在 CI 上会因缺 Redis 或数据串场而挂。

[ProductImportMultipartIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportMultipartIntegrationTest.java)（5 用例）：
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
- 导入超过 1000 行断言被拒绝。

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
3. **读服务缓存**：分类树、商品列表/详情命中率高，后续接 Redis 缓存 key（`product:category:tree`、`product:detail:{id}`、`product:page:{pageNum}:{size}:{filters}`），配合写后失效。
4. **sku_code 唯一校验的并发窗口**：当前 DB 已建 `uk_sku_code`，业务代码仅做前置提示（导入路径已按索引口径预查，见 5.4）。若并发创建不同商品但 sku_code 相同，最终会由数据库唯一约束抛 DataIntegrityViolationException，后续可在 `GlobalExceptionHandler` 统一转成 2012。
5. **SKU 启用/停用**：`ProductSku.status` 已落库，当前查询未区分 status。MVP 按「全部返回」简化；后续 minPrice、前台展示仅统计 status=1 的 SKU。
6. **富文本安全**：Product.detail 是 HTML 富文本，后续上线前台展示前增加 XSS 过滤（Spring Security headers + 内容清洗库）。
7. **查询条件扩展**：关键词模糊目前仅对 `name like`；后续可扩 `subtitle`、`category_id 递归子分类`、品牌等更贴近电商实际搜索。
8. **导入异步化**：现在导入在同步请求内完成（商品 1000 行上限）。若要支持上万行，需要改成「上传 → 异步任务 → 结果下载」，同时把当前「部分成功」的结果从响应体改为可下载的结果文件。

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

- [ExcelSupport.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ExcelSupport.java) / [ExcelRow.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ExcelRow.java) / [ImportError.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/excel/ImportError.java)（POI 薄封装，公共模块）
- [ProductImportServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductImportServiceImpl.java)
- [ProductImportResultVO.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/vo/ProductImportResultVO.java)
- [ProductSkuMapper.selectOccupiedSkuCodes](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductSkuMapper.java)（与唯一索引口径一致的占用查询）
- [AdminProductController.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java)（`/import` 与 `/import/template`）
- [ProductImportServiceImplTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductImportServiceImplTest.java)
- [ProductImportIntegrationTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductImportIntegrationTest.java)
