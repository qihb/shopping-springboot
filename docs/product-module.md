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

## 六、控制器与权限

- 后台统一前缀 `/api/admin/**`，被 `SecurityConfig.adminSecurityFilterChain` 的 `adminPrincipalAuthorizationManager` 严格校验必须是 `AdminUserPrincipal`（见 [SecurityConfig.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/java/com/springshop/web/security/SecurityConfig.java)）。
- 控制器方法级按钮权限：
  - 分类：`product:category:{list|create|update|delete}`（由 [AdminCategoryController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminCategoryController.java) 的 `@PreAuthorize` 生效）。
  - 商品：`product:product:{list|create|update}`（由 [AdminProductController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/admin/AdminProductController.java) 的 `@PreAuthorize` 生效）。
- 前台公开接口（SecurityConfig 白名单 permitAll）：
  - `GET /api/categories/tree` → [AppCategoryController.tree](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/app/AppCategoryController.java)
  - `GET /api/products`、`GET /api/products/{id}` → [AppProductController](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/controller/app/AppProductController.java)

### 关于操作审计注解的取舍

MVP 阶段未在 product 后台接口直接使用 admin 模块的 `@OperationLog` 注解。根本原因：`@OperationLog` 与其切面在 `spring-shop-admin` 内部，强依赖 admin 的 entity/mapper/principal；若 product 直接 import admin 包将导致依赖倒置。

当前取舍：

- RBAC 按钮级权限仍然通过 product 模块自带的 `spring-security-core` 的 `@PreAuthorize` 保证生效；
- 后续可在 admin 模块扩展 AOP 切点，对路径匹配 `/api/admin/**` 的控制器调用统一写入操作日志（此时 product 无需任何改动），保持单向依赖。

## 七、菜单初始化

[AdminDataInitializer.buildMenus()](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java#L113-L133) 新增：

- 商品管理（目录）
  - 分类管理（菜单）
    - 新增/修改/删除分类（按钮权限，权限标识 `product:category:create/update/delete`）
  - 商品管理（菜单）
    - 新增/修改商品（按钮权限 `product:product:create/update`）

MVP 首次启动时由 data initializer 将上述菜单写入 `sys_menu`，配合 `product:category:list` / `product:product:list` 的菜单级权限被 Spring Security 的 `@PreAuthorize` 校验。

## 八、测试基座

### 8.1 单元测试

全部使用 JUnit5 + Mockito，运行无需 MySQL/Redis。

- [CategoryServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/category/service/impl/CategoryServiceImplTest.java)（5 用例）：create/updateNotFound/deleteHasChildren/getById/tree。打桩小技巧：`selectCount(Wrappers.<ProductCategory>lambdaQuery().eq(...))` 的严格匹配会触发 Mockito `PotentialStubbingProblem`，替换为 `any()` 即可。
- [ProductManageServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductManageServiceImplTest.java)（5 用例）：SKU 空、分类不存在、SKU 编码重复、创建成功、上下架不存在。
- [ProductQueryServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/test/java/com/springshop/product/product/service/impl/ProductQueryServiceImplTest.java)（5 用例）：adminDetail 不存在 / appDetail 不存在 / appDetail 下架 / adminDetail 聚合 / adminPage 分页+分类名+最低价。
  - 单测预热 MyBatis-Plus Lambda cache：`@BeforeAll warmupMybatisPlusLambdaCache()` 手动 `TableInfoHelper.initTableInfo`，否则 `LambdaQueryWrapper` 在走 Mockito 打桩前因无 lambda 元数据抛异常。
  - 存在一些共享桩，类上使用 `@MockitoSettings(strictness = Strictness.LENIENT)` 规避 UnnecessaryStubbingException。

### 8.2 集成测试

[ProductIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java)：走完整 Spring 容器，使用 H2 内存库（MySQL 兼容模式）+ Flyway `db/migration-test` 脚本。当前覆盖：

- `admin_category_tree_should_require_login`：未登录访问 `/api/admin/categories/tree` → 401
- `app_category_tree_should_public`：匿名访问 `/api/categories/tree` → 200 + Result.code = 200
- `admin_product_page_should_require_login`：未登录访问 `/api/admin/products` → 401
- `app_product_page_should_public`：匿名访问 `/api/products?current=1&size=5` → 200 + Result.code = 200

可继续扩展的集成测试方向（后续 TODO）：

- 用管理员 token 调用后台创建分类/商品，断言 DB 实际插入；
- 前台商品详情访问下架 SPU 断言 code = 2014；
- SKU 编码重复跨商品的创建失败场景。

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

1. **操作日志**：在 admin 模块扩展 AOP 切点统一记录 `/api/admin/**` 的 controller 调用（解决 product 不引用 admin 的分层约束）。
2. **写服务性能**：当前 SKU/Image 更新是「先 delete 再重插」，后续可改为按 id 做真正增量 upsert，减少 delete+insert 对索引/自增 ID 的冲击；大量 SKU 时可走批量 insert。
3. **读服务缓存**：分类树、商品列表/详情命中率高，后续接 Redis 缓存 key（`product:category:tree`、`product:detail:{id}`、`product:page:{pageNum}:{size}:{filters}`），配合写后失效。
4. **sku_code 唯一校验的并发窗口**：当前 DB 已建 `uk_sku_code`，业务代码仅做前置提示。若并发创建不同商品但 sku_code 相同，最终会由数据库唯一约束抛 DataIntegrityViolationException，后续可在 `GlobalExceptionHandler` 统一转成 2012。
5. **SKU 启用/停用**：`ProductSku.status` 已落库，当前查询未区分 status。MVP 按「全部返回」简化；后续 minPrice、前台展示仅统计 status=1 的 SKU。
6. **富文本安全**：Product.detail 是 HTML 富文本，后续上线前台展示前增加 XSS 过滤（Spring Security headers + 内容清洗库）。
7. **查询条件扩展**：关键词模糊目前仅对 `name like`；后续可扩 `subtitle`、`category_id 递归子分类`、品牌等更贴近电商实际搜索。

## 十、关键修改文件一览（按 task commit 顺序）

参见父 POM、各模块 POM、ResultCode、AdminDataInitializer、SecurityConfig 等关键改动的真实文件：

- [父 pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/pom.xml#L21-L27)（注册 product 模块）
- [spring-shop-product/pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/pom.xml)（含 spring-security-core）
- [spring-shop-web/pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/pom.xml#L26-L33)（依赖 product）
- [ResultCode.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java#L21-L29)（2000 段商品错误码）
- [SecurityConfig.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/java/com/springshop/web/security/SecurityConfig.java#L88-L94)（前台公开路径放行）
- [AdminDataInitializer.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java#L121-L133)（商品管理菜单与按钮权限初始化）
- [CategoryServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/category/service/impl/CategoryServiceImpl.java)
- [ProductManageServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductManageServiceImpl.java)
- [ProductQueryServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-product/src/main/java/com/springshop/product/product/service/impl/ProductQueryServiceImpl.java)
- [ProductIntegrationTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/ProductIntegrationTest.java)
