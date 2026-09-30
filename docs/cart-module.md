# 购物车模块（spring-shop-cart）技术梳理

本文档梳理 `spring-shop-cart` 模块的实现，包含模块职责、分层结构、数据模型、关键设计决策、服务与接口、测试基座与后续可扩展点。

## 一、模块职责与边界

**模块名**：`spring-shop-cart`，隶属 spring-shop 单体业务模块之一，**纯前台模块**（无管理后台入口）。

- **上游依赖**：`spring-shop-common`（统一响应、JSR303 校验、MyBatis-Plus）、`spring-shop-product`（复用 SKU / 商品的只读查询）。
- **下游被依赖**：`spring-shop-web`（启动模块），负责把 cart 控制器扫入 Spring 容器、加载 Mapper。
- **依赖方向**：`web → cart → product → common`，单向无环；cart 只读取商品数据，不回写。
- **当前范围**：加购、改数量、单条/全选勾选、删除单条、删除已勾选、清空、购物车列表（含失效标记与汇总金额）。

## 二、目录与分层结构

```
spring-shop-cart/
└── src/main/java/com/springshop/cart/
    ├── controller/CartController.java
    ├── dto/{CartAddRequest, CartQuantityRequest, CartCheckedRequest}.java
    ├── entity/CartItem.java
    ├── mapper/CartItemMapper.java
    ├── service/{CartService.java, impl/CartServiceImpl.java}
    └── vo/{CartItemVO.java, CartVO.java}
```

- 与 user 模块一致采用扁平分层（不按子域拆包），因为购物车只有一个业务对象。
- 严格遵循 AGENTS.md 分层：`controller → service(interface + impl) → mapper/entity`，入参 DTO、出参 VO，业务失败统一抛 `BusinessException`。

## 三、数据模型（复用 V3 Flyway 脚本）

表：`cart_item`（定义在 [V3__init_order_schema.sql](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/main/resources/db/migration/V3__init_order_schema.sql#L29-L42)），**本模块未新增任何迁移脚本**。

| 字段 | 说明 |
|------|------|
| `user_id` + `sku_id` | 唯一键 `uk_user_sku`，保证同一用户同一 SKU 仅一条记录 |
| `quantity` | 购买数量，加购时累加 |
| `checked` | 是否勾选：1 勾选 / 0 未勾选 |
| `is_deleted` / `version` | 表上存在，但 **实体不映射**（见下方设计决策） |

### 关键设计决策：物理删除（不映射 is_deleted）

`CartItem` 实体刻意**不**声明 `is_deleted` 字段（与商品模块不同）：

- 表上有唯一键 `uk_user_sku(user_id, sku_id)`。若走逻辑删除，「删除某 SKU 后再加购同一 SKU」会插入新行，而旧行（`is_deleted=1`）仍占用唯一键 → **唯一键冲突**。
- 购物车是临时数据，真正的商品信息会在**下单时做快照**，因此物理删除才是正确语义。
- 「删除后再次加购同一 SKU」被写成守门测试（集成测试 `delete_then_add_same_sku_should_succeed`）保护该决策。

## 四、错误码段位

[ResultCode.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java) 购物车模块占用 3000 段：

```
3001 CART_ITEM_NOT_FOUND      购物车条目不存在
3002 CART_QUANTITY_INVALID    购买数量不合法
3003 CART_STOCK_INSUFFICIENT  库存不足
3004 CART_SKU_DISABLED        该规格已停售
```

同时**复用商品模块错误码**（同域语义，不重复造码）：

- `2011 PRODUCT_SKU_NOT_FOUND`：加购时 SKU 不存在
- `2014 PRODUCT_OFF_SHELF`：商品已下架（SPU 不存在或 status != 1）

## 五、接口清单（均需登录）

用户 id 由认证过滤器写入 `UserContext`，Controller 读取后传入 Service，前端无需传参。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/cart/items` | 加购 `{skuId, quantity}`；同 SKU 已存在则累加并置为勾选 |
| GET | `/api/cart` | 购物车列表 + 汇总 |
| PUT | `/api/cart/items/{id}` | 改数量 `{quantity}` |
| PUT | `/api/cart/items/{id}/checked` | 单条勾选 `{checked}` |
| PUT | `/api/cart/checked` | 全选 / 全不选 `{checked}` |
| DELETE | `/api/cart/items/{id}` | 删除单条 |
| DELETE | `/api/cart/checked` | 删除已勾选 |
| DELETE | `/api/cart` | 清空 |

**安全配置零改动**：`/api/cart/**` 不在 `SecurityConfig#appSecurityFilterChain` 的白名单里，被 `anyRequest().authenticated()` 覆盖，天然要求登录。

## 六、关键服务设计（CartServiceImpl）

### 6.1 写操作

- **add**：依次校验 数量合法（3002）→ SKU 存在（2011）→ SKU 启用（3004）→ 商品在售（2014）→ 累加后是否超库存（3003）。
  - `targetQuantity = 本次数量 + 已存在数量`，超 `sku.stock` 即拒绝。
  - 不存在则 insert（`checked=1`），已存在则累加并置 `checked=1`。
- **updateQuantity**：校验数量合法（3002）→ 条目归属（3001）→ 超库存校验（3003，SKU 已删除时跳过）。
- **updateChecked / updateAllChecked**：单条走 `updateById`；全选走一条 `LambdaUpdateWrapper` 批量 UPDATE。
- **delete / deleteChecked / clear**：删除前校验归属；批量删除走 `LambdaQueryWrapper` 条件删除（非逻辑删除）。

### 6.2 越权防护

所有针对单条目的操作都通过 `requireOwnedItem(userId, id)`：条目不存 **或** `userId` 不匹配一律抛 3001，避免用户操作他人购物车。

### 6.3 list 聚合（避免 N+1）

1. 查出当前用户全部条目（`orderByDesc(id)`）；
2. 收集 `skuId` 集合 → **一次**批量查 SKU；再收集 `productId` 集合 → **一次**批量查商品，组装成 Map；
3. 逐条构建 `CartItemVO`，并打 **失效标记**：
   - SKU 查不到 → `invalid=true`，原因「商品已失效」；
   - SKU `status != 1` → 「该规格已停售」；
   - 商品不存在或 `status != 1` → 「商品已下架」；
4. 汇总：`totalQuantity` 统计全部条目数量；`checkedQuantity` / `checkedAmount` **只统计有效且勾选的条目**（失效条目即使勾选也不计入结算）。

## 七、测试基座

### 7.1 单元测试

[CartServiceImplTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-cart/src/test/java/com/springshop/cart/service/impl/CartServiceImplTest.java)（22 用例，纯 Mockito，无需 Spring / MySQL / Redis）：

- 写：加购新增 / 已存在累加并勾选 / SKU 不存在 / 商品下架 / 规格停售 / 库存不足 / 累加后超库存；
- 改：数量非法 / 条目不存在 / 库存不足 / 正常更新；勾选更新 / 条目不存在；全选批量更新；
- 删：条目不存在 / 越权（他人条目）不删 / 正常删除；删除已勾选 / 清空；
- 读：空车汇总为 0 / 聚合（商品名、小计、总数、勾选数、勾选金额、失效标记）/ SKU 被删标记失效。

沿用 MyBatis-Plus 预热：`@BeforeAll warmupMybatisPlusLambdaCache()` 手动 `TableInfoHelper.initTableInfo(CartItem/Product/ProductSku)`，避免 `LambdaQueryWrapper` 在打桩前因缺 lambda 元数据抛异常；类上 `@MockitoSettings(strictness = LENIENT)` 兼容共享桩。

### 7.2 集成测试

[CartIntegrationTest](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/CartIntegrationTest.java)（5 用例，H2 + Flyway `db/migration-test`）：

- `cart_should_require_login`：未登录访问 `/api/cart` → 401；
- `add_then_list_should_return_item`：注册登录 → 加购 → 列表返回商品名/数量/小计/汇总；
- `add_same_sku_twice_should_accumulate`：重复加购同 SKU 数量累加；
- `delete_then_add_same_sku_should_succeed`：**物理删除决策的守门测试**；
- `add_off_shelf_product_should_return_off_shelf`：加购下架商品 → 2014。

集成测试通过 Mapper 直接插入 `product` / `product_sku` 造数，类头满足「四件套」：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional` + `@MockBean StringRedisTemplate`。

### 7.3 运行命令

```bash
# 仅跑 cart 模块单测
mvn -pl spring-shop-cart -am test -Dsurefire.failIfNoSpecifiedTests=false

# 仅跑购物车集成测试
mvn -pl spring-shop-web -am test -Dtest=CartIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false

# 全量回归（等价 CI）
mvn clean verify
```

## 八、后续迭代建议（YAGNI 暂不做）

1. **角标计数接口**：`GET /api/cart/count` 供导航栏显示件数。
2. **失效条目自动清理**：可由定时任务或进入购物车时惰性清理。
3. **加购并发去重**：当前「先查后写」存在并发窗口，可捕获唯一键冲突兜底转为累加。
4. **Redis 缓存**：购物车读多写少，可将 `cart:{userId}` 放 Redis（Hash），落库异步化。
5. **价格变动提示**：列表返回「加入时价格 / 当前价格」对比，提示用户价格变化。

## 九、关键修改文件一览

- [父 pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/pom.xml)（注册 cart 模块 + 声明版本）
- [spring-shop-cart/pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-cart/pom.xml)（依赖 common + product）
- [spring-shop-web/pom.xml](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/pom.xml)（依赖 cart）
- [ResultCode.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java)（3000 段购物车错误码）
- [CartItem.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-cart/src/main/java/com/springshop/cart/entity/CartItem.java)（不映射 isDeleted）
- [CartServiceImpl.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-cart/src/main/java/com/springshop/cart/service/impl/CartServiceImpl.java)
- [CartController.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-cart/src/main/java/com/springshop/cart/controller/CartController.java)
- [CartServiceImplTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-cart/src/test/java/com/springshop/cart/service/impl/CartServiceImplTest.java)
- [CartIntegrationTest.java](file:///Users/qihaibing/Documents/Trae/spring_shop/spring-shop-web/src/test/java/com/springshop/web/CartIntegrationTest.java)
