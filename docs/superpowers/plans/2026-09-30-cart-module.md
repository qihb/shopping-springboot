# 购物车模块实施计划（spring-shop-cart）

**Goal:** 新增购物车模块，支持加购、改数量、勾选、删除、清空与购物车列表（含汇总金额），配套单元测试 + H2 集成测试全绿。

**Architecture:** 新增 `spring-shop-cart` Maven 模块，扁平分层（controller/service/mapper/entity/dto/vo，与 user 模块一致），只提供前台接口且必须登录。模块单向依赖 `spring-shop-product` 复用 SKU/商品只读查询，形成 `web → cart → product → common`，不引入反向依赖。

**Tech Stack:** Java 21 + Spring Boot 3.5.16 + MyBatis-Plus 3.5.17 + Spring Security(JWT) + JUnit5 + Mockito + H2

---

## 一、关键设计决策

| 决策 | 说明 |
|------|------|
| 复用商品错误码 | SKU 不存在用 2011 `PRODUCT_SKU_NOT_FOUND`、商品下架用 2014 `PRODUCT_OFF_SHELF`（同域语义，不重复造码） |
| 新增购物车错误码 | 3000 段：3001 条目不存在 / 3002 数量不合法 / 3003 库存不足 / 3004 规格已停售 |
| **物理删除** | `CartItem` 实体**不映射 `is_deleted`**。表上有唯一键 `uk_user_sku(user_id, sku_id)`，若走逻辑删除，「删掉某 SKU 再加购同一 SKU」会与旧行唯一键冲突。购物车是临时数据（订单会做快照），物理删除是正确语义 |
| 无 Flyway 变更 | `cart_item` 已在 V3 主脚本与 `migration-test` 副本中，索引名 `uk_user_sku` 无重名问题 |
| 无安全配置变更 | `/api/cart/**` 不在白名单，被 `anyRequest().authenticated()` 覆盖，天然要求登录 |
| 无后台入口 | 纯前台模块，不动 `AdminDataInitializer` 与 `@PreAuthorize` |

## 二、接口清单（均需登录，用户 id 取自 `UserContext`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/cart/items` | 加购 `{skuId, quantity}`；同 SKU 已存在则累加并置为勾选 |
| GET | `/api/cart` | 购物车列表 + 汇总（总件数 / 勾选件数 / 勾选金额） |
| PUT | `/api/cart/items/{id}` | 改数量 `{quantity}` |
| PUT | `/api/cart/items/{id}/checked` | 单条勾选 `{checked}` |
| PUT | `/api/cart/checked` | 全选/全不选 `{checked}` |
| DELETE | `/api/cart/items/{id}` | 删除单条 |
| DELETE | `/api/cart/checked` | 删除已勾选 |
| DELETE | `/api/cart` | 清空 |

## 三、文件清单

**配置改 3 处**
- Modify: `pom.xml`（注册 module + dependencyManagement 声明 cart 版本）
- Create: `spring-shop-cart/pom.xml`（common + product + mybatis-plus + validation + swagger-annotations + test）
- Modify: `spring-shop-web/pom.xml`（依赖 cart）
- Modify: `spring-shop-common/.../result/ResultCode.java`（3000 段错误码）

**新增模块**
```
spring-shop-cart/src/main/java/com/springshop/cart/
├── controller/CartController.java
├── dto/{CartAddRequest,CartQuantityRequest,CartCheckedRequest}.java
├── entity/CartItem.java            # 不映射 isDeleted
├── mapper/CartItemMapper.java
├── service/CartService.java
├── service/impl/CartServiceImpl.java
└── vo/{CartItemVO,CartVO}.java
```

**测试与文档**
- Create: `spring-shop-cart/src/test/java/com/springshop/cart/service/impl/CartServiceImplTest.java`
- Create: `spring-shop-web/src/test/java/com/springshop/web/CartIntegrationTest.java`
- Create: `docs/cart-module.md`；Modify: `README.md` 业务规划状态

## 四、任务分解

- [x] **Task 1 模块骨架**：建 cart pom → 父 pom 注册 + 声明版本 → web pom 加依赖 → `mvn -q compile` 通过 → 提交
- [x] **Task 2 错误码**：ResultCode 增加 3001~3004 → 编译 → 提交
- [x] **Task 3 持久层**：`CartItem`（含"为何不用逻辑删除"注释）+ `CartItemMapper` → 编译 → 提交
- [x] **Task 4 契约层**：3 个 DTO（`@NotNull/@Min`）+ `CartItemVO`/`CartVO` + `CartService` 接口 → 编译 → 提交
- [x] **Task 5 写操作**：先写单测（新增/累加/SKU 不存在/下架/规格停售/超库存/条目不存在/全选）→ 跑测试确认失败 → 实现 `CartServiceImpl` 写方法 → 跑测试转绿 → 提交
- [x] **Task 6 读操作**：补列表聚合单测（商品名/规格/价格/小计/汇总/失效标记）→ 跑测试确认失败 → 实现 `list()`（批量查 SKU→商品，避免 N+1）→ 转绿 → 提交
- [x] **Task 7 接口层**：`CartController` + `CartIntegrationTest`（未登录 401、加购成功、重复加购累加、**删除后再次加购成功**、下架商品加购返回 2014）→ 提交
- [x] **Task 8 收尾**：`mvn clean verify` 全绿 → 补 `docs/cart-module.md` 与 README 状态 → 提交

## 五、验证要点

```bash
mvn -pl spring-shop-cart -am test -Dsurefire.failIfNoSpecifiedTests=false   # 单测
mvn -pl spring-shop-web -am test -Dtest=CartIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn clean verify                                                            # 等价 CI
```

- 单测需沿用 MyBatis-Plus 预热：`@BeforeAll` 调 `TableInfoHelper.initTableInfo(CartItem/Product/ProductSku)`，并加 `@MockitoSettings(strictness = LENIENT)`
- 集成测试类头 4 件套：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional` + `@MockBean StringRedisTemplate`
- 「删除后再次加购同一 SKU」是物理删除决策的守门测试，必须覆盖

## 六、暂不做（YAGNI）

角标计数接口、失效条目自动清理、加购并发去重（唯一键冲突兜底转 3001）、购物车入 Redis 缓存、价格变动提示 —— 留待订单模块或后续迭代。
