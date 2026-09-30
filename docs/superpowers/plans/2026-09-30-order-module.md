# 订单模块实施计划（spring-shop-order）

**Goal:** 新增订单模块，覆盖「收货地址管理 → 购物车勾选项下单（快照 + 扣库存）→ 订单列表/详情 → 支付/取消/确认收货 → 后台发货」全链路，配套单元测试 + H2 集成测试全绿。

**Architecture:** 新增 `spring-shop-order` Maven 模块，扁平分层（controller/service/mapper/entity/dto/vo，与 user / cart 模块一致）。前台接口全部要求登录，用户 id 取自 `UserContext`；后台接口挂在 `/api/admin/orders`，复用既有 admin 过滤链 + `@PreAuthorize`。模块依赖 `spring-shop-cart`（复用 `CartItemMapper` 读取/清理勾选项）与 `spring-shop-product`（复用 SKU/商品只读 + 扣减库存），形成 `web → order → {cart, product} → common` 单向依赖，无环。表结构已在 V3 就绪，**无 Flyway 变更**。

**Tech Stack:** Java 21 + Spring Boot 3.5.16 + MyBatis-Plus 3.5.17 + Spring Security(JWT) + JUnit5 + Mockito + H2

---

## 一、关键设计决策

| 决策 | 说明 |
|------|------|
| **下单来源 = 购物车勾选项** | `POST /api/orders` 只传 `{addressId, remark}`，服务端读取当前用户 `checked = 1` 的购物车条目。前端「立即购买」后续迭代再支持（YAGNI） |
| **库存扣减用条件更新** | `UPDATE product_sku SET stock = stock - #{qty} ... WHERE id = #{skuId} AND stock >= #{qty}`，影响行数 0 即库存不足抛 4004。不引入分布式锁/Redis 预扣，属于「乐观扣减 + 唯一订单号」的入门级并发方案，与 `collaboration-plan.md` 第五节的规划一致 |
| **下单在同一事务内完成「校验 → 扣库存 → 写订单/明细 → 清购物车勾选项」** | 任一步失败（含库存不足）整体回滚，库存与购物车不会被写脏 |
| **快照不可漂移** | 收货人/电话/地址拼接、商品名/规格/主图/成交单价全部落库快照，后续改地址、改商品不影响历史订单 |
| **金额只用 BigDecimal** | `totalAmount` 与 `payAmount` 暂相等（无优惠券/运费，YAGNI），`DECIMAL(10,2)` |
| **订单号规则** | `yyyyMMddHHmmssSSS` + userId 后 3 位 + 3 位随机数，长度 ≤ 32，靠 `uk_order_no` 兜底。冲突概率极低，MVP 不做重试 |
| **状态流转集中校验** | 统一在 `OrderStatus` + 私有方法 `requireStatus(order, expect)` 校验，非法流转抛 4006，杜绝散落 if |
| **取消回滚库存** | 仅在「待付款 → 已取消」时回滚库存与销量；已支付订单不可取消（退款属后续迭代） |
| **物理删除购物车条目** | 复用 cart 模块既有语义（`CartItem` 不映射 `is_deleted`），订单成功后按 id 批量物理删除已下单条目 |
| **无 Flyway 变更** | `shipping_address` / `orders` / `order_item` 已在 V3 主脚本与 `migration-test` 副本中，且测试副本索引名已全局唯一（`idx_address_user_id` / `idx_order_user_id` / `idx_order_status`） |
| **无 SecurityConfig 变更** | `/api/orders/**`、`/api/addresses/**` 不在白名单，被 `anyRequest().authenticated()` 覆盖，天然要求登录 |
| **后台需要两处登记** | `AdminDataInitializer#buildMenus` 新增「订单管理 → 订单列表 + 发货」菜单与权限码，Controller 上标注同值 `@PreAuthorize` |

### 错误码（ResultCode 新增 4000 段）

```java
    // 业务码：订单模块（4000 段）
    ORDER_ADDRESS_NOT_FOUND(4001, "收货地址不存在"),
    ORDER_CART_EMPTY(4002, "请先勾选要下单的商品"),
    ORDER_SKU_UNAVAILABLE(4003, "商品已下架或规格已停售"),
    ORDER_STOCK_INSUFFICIENT(4004, "商品库存不足"),
    ORDER_NOT_FOUND(4005, "订单不存在"),
    ORDER_STATUS_ILLEGAL(4006, "当前订单状态不支持该操作"),
```

### 订单状态（`enums/OrderStatus.java`）

```java
/**
 * 订单状态（与 orders.status 字段一一对应）
 */
public enum OrderStatus {

    PENDING_PAYMENT(1, "待付款"),
    PENDING_SHIPMENT(2, "待发货"),
    PENDING_RECEIPT(3, "待收货"),
    FINISHED(4, "已完成"),
    CANCELLED(5, "已取消"),
    REFUNDED(6, "已退款");

    private final Integer code;
    private final String desc;

    OrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static String descOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code.equals(code)) {
                return status.desc;
            }
        }
        return null;
    }
}
```

## 二、接口清单

### 前台收货地址（需登录，`/api/addresses`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/addresses` | 我的地址列表（默认地址排最前） |
| POST | `/api/addresses` | 新增地址（`isDefault = true` 时把其他地址取消默认） |
| PUT | `/api/addresses/{id}` | 修改地址（归属校验） |
| PUT | `/api/addresses/{id}/default` | 设为默认地址 |
| DELETE | `/api/addresses/{id}` | 删除地址（归属校验） |

### 前台订单（需登录，`/api/orders`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/orders` | 下单，body `{addressId, remark}`，返回订单号 |
| GET | `/api/orders` | 我的订单分页（`current/size/status`） |
| GET | `/api/orders/{orderNo}` | 订单详情（含明细快照，越权返回 4005） |
| POST | `/api/orders/{orderNo}/pay` | 模拟支付：待付款 → 待发货 |
| POST | `/api/orders/{orderNo}/cancel` | 取消订单：待付款 → 已取消（回滚库存） |
| POST | `/api/orders/{orderNo}/confirm` | 确认收货：待收货 → 已完成 |

### 后台订单（`/api/admin/orders`，需管理员 + 权限码）

| 方法 | 路径 | 权限码 | 说明 |
|------|------|--------|------|
| GET | `/api/admin/orders` | `order:order:list` | 订单分页（`orderNo` 模糊 + `status` 过滤） |
| POST | `/api/admin/orders/{orderNo}/ship` | `order:order:ship` | 发货：待发货 → 待收货 |

## 三、文件清单

**配置与既有文件改动（6 处）**
- Modify: `pom.xml`（`<modules>` 注册 `spring-shop-order` + `dependencyManagement` 声明版本）
- Create: `spring-shop-order/pom.xml`（依赖 common + product + cart + mybatis-plus + validation + swagger-annotations + test）
- Modify: `spring-shop-web/pom.xml`（依赖 `spring-shop-order`）
- Modify: `spring-shop-common/src/main/java/com/springshop/common/result/ResultCode.java`（4000 段 6 个错误码）
- Modify: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductSkuMapper.java`（`deductStock` / `restoreStock`）
- Modify: `spring-shop-product/src/main/java/com/springshop/product/product/mapper/ProductMapper.java`（`increaseSales` / `decreaseSales`）
- Modify: `spring-shop-admin/src/main/java/com/springshop/admin/config/AdminDataInitializer.java`（订单菜单 + 发货按钮权限）

**新增模块**
```
spring-shop-order/src/main/java/com/springshop/order/
├── controller/OrderController.java              # 前台 /api/orders
├── controller/AddressController.java            # 前台 /api/addresses
├── controller/admin/AdminOrderController.java   # 后台 /api/admin/orders
├── dto/{OrderCreateRequest,OrderPageQuery,AdminOrderPageQuery,AddressSaveRequest}.java
├── entity/{Order,OrderItem,ShippingAddress}.java
├── enums/OrderStatus.java
├── mapper/{OrderMapper,OrderItemMapper,ShippingAddressMapper}.java
├── service/{OrderService,AddressService}.java
├── service/impl/{OrderServiceImpl,AddressServiceImpl}.java
└── vo/{OrderVO,OrderItemVO,AddressVO}.java
```

**测试与文档**
- Create: `spring-shop-order/src/test/java/com/springshop/order/service/impl/OrderServiceImplTest.java`
- Create: `spring-shop-order/src/test/java/com/springshop/order/service/impl/AddressServiceImplTest.java`
- Create: `spring-shop-web/src/test/java/com/springshop/web/OrderIntegrationTest.java`
- Create: `docs/order-module.md`；Modify: `README.md`（模块划分 + 业务规划状态）

## 四、任务分解

### Task 1 模块骨架

**Files:** Modify `pom.xml`、`spring-shop-web/pom.xml`；Create `spring-shop-order/pom.xml`

- [ ] `pom.xml` 的 `<modules>` 在 `spring-shop-cart` 后加 `<module>spring-shop-order</module>`
- [ ] `pom.xml` 的 `<dependencyManagement>` 在 cart 之后加：

```xml
            <dependency>
                <groupId>com.springshop</groupId>
                <artifactId>spring-shop-order</artifactId>
                <version>${project.version}</version>
            </dependency>
```

- [ ] 新建 `spring-shop-order/pom.xml`：复制 `spring-shop-cart/pom.xml`，改 `artifactId` 为 `spring-shop-order`、`description` 为「订单模块：收货地址、下单、订单状态流转与后台发货」，依赖里保留 common / mybatis-plus / validation / swagger-annotations / test，并把 `spring-shop-product` 之外**再加上 `spring-shop-cart`**：

```xml
        <!-- 复用商品模块的 SKU/商品只读查询与库存扣减 -->
        <dependency>
            <groupId>com.springshop</groupId>
            <artifactId>spring-shop-product</artifactId>
        </dependency>
        <!-- 复用购物车条目读写（下单来源 = 勾选项，成功后清理） -->
        <dependency>
            <groupId>com.springshop</groupId>
            <artifactId>spring-shop-cart</artifactId>
        </dependency>
```

- [ ] `spring-shop-web/pom.xml` 加依赖：

```xml
        <dependency>
            <groupId>com.springshop</groupId>
            <artifactId>spring-shop-order</artifactId>
        </dependency>
```

- [ ] 验证：`mvn -q compile`（期望 BUILD SUCCESS，其他模块不受影响）
- [ ] 提交：`feat(order): 新增订单模块骨架`

### Task 2 错误码

**Files:** Modify `spring-shop-common/.../result/ResultCode.java`

- [ ] 在 3000 段之后、5000 段之前插入「一、关键设计决策」中给出的 4000 段 6 个错误码，注释统一为 `// 业务码：订单模块（4000 段）`
- [ ] 验证：`mvn -q -pl spring-shop-common compile`
- [ ] 提交：`feat(order): 新增订单模块错误码（4000 段）`

### Task 3 持久层（实体 + Mapper + 库存扣减）

**Files:** Create `entity/Order.java`、`entity/OrderItem.java`、`entity/ShippingAddress.java`、`mapper/*.java`；Modify 两个商品 Mapper

- [ ] `Order`（`@TableName("orders")`，注意 `order` 是 MySQL 保留字）+ `@TableLogic isDeleted` + `@Version version`，字段与 V3 建表脚本一一对应：

```java
package com.springshop.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体（表名 orders：order 是 MySQL 保留字）
 *
 * <p>收货信息与金额均为下单时快照，不随地址、商品变更而漂移。
 */
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    private Integer status;

    private String receiverName;

    private String receiverPhone;

    private String receiverAddress;

    private String remark;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    private LocalDateTime finishTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;

    @Version
    private Integer version;

    public Order() {
    }

    // getter / setter 全字段
}
```

- [ ] `OrderItem`（`@TableName("order_item")`）：id / orderId / productId / skuId / productName / skuSpecs / productImage / price / quantity / subtotal / createTime / updateTime / isDeleted / version
- [ ] `ShippingAddress`（`@TableName("shipping_address")`）：id / userId / receiverName / receiverPhone / province / city / district / detailAddress / isDefault / createTime / updateTime / isDeleted / version
- [ ] 三个 Mapper 均 `@Mapper ... extends BaseMapper<T>`，与 `CartItemMapper` 完全同构：

```java
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
```

- [ ] 商品侧库存扣减（`ProductSkuMapper` 追加，注意 `@Param`）：

```java
    /**
     * 条件扣减库存：库存不足时影响行数为 0，由调用方判定并抛业务异常
     *
     * <p>单条 UPDATE 由数据库保证原子性，是入门级并发扣减方案（无需分布式锁）。
     */
    @Update("UPDATE product_sku SET stock = stock - #{quantity}, version = version + 1 "
            + "WHERE id = #{skuId} AND stock >= #{quantity}")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /**
     * 回滚库存（订单取消时调用）
     */
    @Update("UPDATE product_sku SET stock = stock + #{quantity}, version = version + 1 "
            + "WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
```

- [ ] 商品销量（`ProductMapper` 追加，取消时用 `GREATEST` 防止减成负数）：

```java
    @Update("UPDATE product SET sales = sales + #{quantity}, version = version + 1 WHERE id = #{productId}")
    int increaseSales(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    @Update("UPDATE product SET sales = GREATEST(sales - #{quantity}, 0), version = version + 1 "
            + "WHERE id = #{productId}")
    int decreaseSales(@Param("productId") Long productId, @Param("quantity") Integer quantity);
```

- [ ] 验证：`mvn -q -pl spring-shop-order -am compile`
- [ ] 提交：`feat(order): 新增订单实体与持久层，商品模块补充库存扣减`

### Task 4 契约层（DTO / VO / OrderStatus / Service 接口）

**Files:** Create `enums/OrderStatus.java`、`dto/*`、`vo/*`、`service/{OrderService,AddressService}.java`

- [ ] `enums/OrderStatus.java` 按「一、关键设计决策」给出的代码创建
- [ ] `dto/OrderCreateRequest.java`：

```java
public class OrderCreateRequest {

    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    @Size(max = 255, message = "买家备注不能超过 255 个字符")
    private String remark;

    // getter / setter
}
```

- [ ] `dto/OrderPageQuery.java`：`extends PageQuery` + `private Integer status;`
- [ ] `dto/AdminOrderPageQuery.java`：`extends PageQuery` + `private String orderNo;` + `private Integer status;`
- [ ] `dto/AddressSaveRequest.java`：

```java
public class AddressSaveRequest {

    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 50, message = "收货人姓名不能超过 50 个字符")
    private String receiverName;

    @NotBlank(message = "收货人手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货人手机号格式不正确")
    private String receiverPhone;

    @NotBlank(message = "省份不能为空")
    @Size(max = 50, message = "省份不能超过 50 个字符")
    private String province;

    @NotBlank(message = "城市不能为空")
    @Size(max = 50, message = "城市不能超过 50 个字符")
    private String city;

    @NotBlank(message = "区/县不能为空")
    @Size(max = 50, message = "区/县不能超过 50 个字符")
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 200, message = "详细地址不能超过 200 个字符")
    private String detailAddress;

    private Boolean isDefault;

    // getter / setter
}
```

- [ ] `vo/AddressVO.java`：id / receiverName / receiverPhone / province / city / district / detailAddress / isDefault
- [ ] `vo/OrderItemVO.java`：productId / skuId / productName / skuSpecs / productImage / price / quantity / subtotal
- [ ] `vo/OrderVO.java`：id / orderNo / totalAmount / payAmount / status / statusDesc / receiverName / receiverPhone / receiverAddress / remark / createTime / payTime / shipTime / finishTime / cancelTime / `List<OrderItemVO> items`
- [ ] Service 接口（方法签名固定，后续任务严格对齐）：

```java
public interface AddressService {

    /** 我的地址列表（默认地址在前） */
    List<AddressVO> list(Long userId);

    /** 新增地址，返回地址 id */
    Long create(Long userId, AddressSaveRequest request);

    /** 修改地址（归属校验） */
    void update(Long userId, Long id, AddressSaveRequest request);

    /** 设为默认地址 */
    void setDefault(Long userId, Long id);

    /** 删除地址（归属校验） */
    void delete(Long userId, Long id);
}

public interface OrderService {

    /** 下单：来源为购物车勾选项，返回订单号 */
    String create(Long userId, OrderCreateRequest request);

    /** 我的订单分页 */
    PageResult<OrderVO> pageMine(Long userId, OrderPageQuery query);

    /** 订单详情（越权视为不存在） */
    OrderVO detail(Long userId, String orderNo);

    /** 模拟支付：待付款 → 待发货 */
    void pay(Long userId, String orderNo);

    /** 取消订单：待付款 → 已取消，回滚库存 */
    void cancel(Long userId, String orderNo);

    /** 确认收货：待收货 → 已完成 */
    void confirm(Long userId, String orderNo);

    /** 后台订单分页 */
    PageResult<OrderVO> pageForAdmin(AdminOrderPageQuery query);

    /** 后台发货：待发货 → 待收货 */
    void ship(String orderNo);
}
```

- [ ] 验证：`mvn -q -pl spring-shop-order -am compile`
- [ ] 提交：`feat(order): 新增订单模块契约层与状态枚举`

### Task 5 收货地址服务（TDD）

**Files:** Test: `spring-shop-order/src/test/java/com/springshop/order/service/impl/AddressServiceImplTest.java`；Impl: `service/impl/AddressServiceImpl.java`

- [ ] **Step 1 写失败测试**：`@ExtendWith(MockitoExtension.class)` + `@MockitoSettings(strictness = LENIENT)` + `@Mock ShippingAddressMapper` + `@InjectMocks AddressServiceImpl`，覆盖：

```java
    @Test
    void create_should_clear_other_default_when_is_default_true();   // verify update(...) 被调用一次
    @Test
    void create_should_not_touch_other_default_when_is_default_false();
    @Test
    void list_should_order_default_first();                          // 断言 VO 顺序
    @Test
    void update_should_fail_when_address_not_owned();                // 4001
    @Test
    void set_default_should_clear_old_default_then_set_new();
    @Test
    void delete_should_fail_when_address_not_found();                // 4001
```

- [ ] **Step 2 跑测试确认失败**：`mvn -pl spring-shop-order -am test -Dtest=AddressServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3 实现**（要点：`@Transactional` 改默认；归属校验失败一律 4001）：

```java
@Service
public class AddressServiceImpl implements AddressService {

    private final ShippingAddressMapper shippingAddressMapper;

    public AddressServiceImpl(ShippingAddressMapper shippingAddressMapper) {
        this.shippingAddressMapper = shippingAddressMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, AddressSaveRequest request) {
        ShippingAddress address = new ShippingAddress();
        address.setUserId(userId);
        applyRequest(address, request);
        boolean isDefault = Boolean.TRUE.equals(request.getIsDefault());
        address.setIsDefault(isDefault ? 1 : 0);
        if (isDefault) {
            clearDefault(userId);
        }
        shippingAddressMapper.insert(address);
        return address.getId();
    }
    // update / setDefault / delete 同理，均先 requireOwned(userId, id)
    // clearDefault：LambdaUpdateWrapper eq(userId).eq(isDefault,1).set(isDefault,0)
}
```

- [ ] **Step 4 跑测试确认通过**
- [ ] **Step 5 提交**：`feat(order): 实现收货地址服务与单元测试`

### Task 6 下单核心（TDD，重点任务）

**Files:** Test: `OrderServiceImplTest.java`；Impl: `service/impl/OrderServiceImpl.java`

- [ ] **Step 1 写失败测试**（沿用 cart 单测的 MyBatis-Plus 预热模式：`@BeforeAll` 调 `TableInfoHelper.initTableInfo` 预热 `Order / OrderItem / ShippingAddress / CartItem / Product / ProductSku`），覆盖：

```java
    @Test
    void create_should_fail_when_address_not_owned();          // 4001
    @Test
    void create_should_fail_when_no_checked_cart_item();       // 4002
    @Test
    void create_should_fail_when_sku_off_sale();               // 4003（SKU status != 1 或商品 status != 1）
    @Test
    void create_should_fail_when_deduct_stock_returns_zero();  // 4004（mock deductStock 返回 0）
    @Test
    void create_should_insert_order_and_items_with_snapshot(); // 断言订单号前缀、金额合计、快照字段、状态=1
    @Test
    void create_should_delete_only_checked_cart_items();       // 断言删除条件用的 id 集合
    @Test
    void create_should_fail_when_cart_item_sku_missing();      // 2011
```

- [ ] **Step 2 跑测试确认失败**
- [ ] **Step 3 实现**（核心方法，事务边界与顺序严格照抄）：

```java
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShippingAddressMapper shippingAddressMapper;
    private final CartItemMapper cartItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductMapper productMapper;

    // 构造器注入 6 个依赖

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(Long userId, OrderCreateRequest request) {
        ShippingAddress address = shippingAddressMapper.selectById(request.getAddressId());
        if (address == null || !Objects.equals(address.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_ADDRESS_NOT_FOUND);
        }

        List<CartItem> checkedItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getChecked, 1)
                .orderByAsc(CartItem::getId));
        if (checkedItems.isEmpty()) {
            throw new BusinessException(ResultCode.ORDER_CART_EMPTY);
        }

        // 批量查 SKU / 商品，避免 N+1
        Map<Long, ProductSku> skuMap = loadSkus(checkedItems);
        Map<Long, Product> productMap = loadProducts(skuMap.values());

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem cartItem : checkedItems) {
            ProductSku sku = requireOnSaleSku(cartItem.getSkuId(), skuMap, productMap);
            int quantity = cartItem.getQuantity();
            // 条件扣减：库存不足影响 0 行 → 抛异常，整个事务回滚（已扣的库存一并回滚）
            if (productSkuMapper.deductStock(sku.getId(), quantity) == 0) {
                throw new BusinessException(ResultCode.ORDER_STOCK_INSUFFICIENT);
            }
            productMapper.increaseSales(sku.getProductId(), quantity);

            BigDecimal subtotal = sku.getPrice().multiply(BigDecimal.valueOf(quantity));
            totalAmount = totalAmount.add(subtotal);
            orderItems.add(buildOrderItem(sku, productMap.get(sku.getProductId()), quantity, subtotal));
        }

        Order order = new Order();
        order.setOrderNo(generateOrderNo(userId));
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverAddress(joinAddress(address));
        order.setRemark(request.getRemark());
        orderMapper.insert(order);

        for (OrderItem item : orderItems) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        }

        // 与订单同事务：下单失败则购物车不变
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .in(CartItem::getId, checkedItems.stream().map(CartItem::getId).toList()));

        return order.getOrderNo();
    }

    /**
     * 订单号：时间戳（毫秒）+ 用户 id 后 3 位 + 3 位随机数，唯一键 uk_order_no 兜底
     */
    private String generateOrderNo(Long userId) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String userSuffix = String.format("%03d", Math.abs(userId) % 1000);
        String random = String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
        return timestamp + userSuffix + random;
    }

    private String joinAddress(ShippingAddress address) {
        return address.getProvince() + address.getCity() + address.getDistrict() + address.getDetailAddress();
    }
}
```

- [ ] **Step 4 跑测试确认通过**
- [ ] **Step 5 提交**：`feat(order): 实现下单核心逻辑（快照、扣库存、清购物车）与单元测试`

### Task 7 订单查询（分页 + 详情）

**Files:** Modify `OrderServiceImpl.java`、`OrderServiceImplTest.java`

- [ ] **Step 1 补失败测试**：

```java
    @Test
    void pageMine_should_filter_by_user_and_status();     // 断言 wrapper 条件 + VO 字段（statusDesc、金额、件数）
    @Test
    void detail_should_fail_when_order_belongs_to_other_user();  // 4005
    @Test
    void detail_should_return_items_with_snapshot();
```

- [ ] **Step 2 实现要点**（列表与详情共用 `toVO(order, itemMap)`，明细按 `order_id in (本页订单 id)` 批量查，避免 N+1）：

```java
    @Override
    public PageResult<OrderVO> pageMine(Long userId, OrderPageQuery query) {
        Page<Order> page = orderMapper.selectPage(query.toPage(), new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(query.getStatus() != null, Order::getStatus, query.getStatus())
                .orderByDesc(Order::getId));

        Map<Long, List<OrderItem>> itemMap = loadItems(page.getRecords());
        List<OrderVO> records = page.getRecords().stream()
                .map(order -> toVO(order, itemMap.getOrDefault(order.getId(), List.of())))
                .toList();

        PageResult<OrderVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(page.getTotal());
        result.setPages(page.getPages());
        result.setCurrent(page.getCurrent());
        result.setSize(page.getSize());
        return result;
    }

    /** 明细批量查询：order_id in (...) 一次性取出，按 orderId 分组 */
    private Map<Long, List<OrderItem>> loadItems(List<Order> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }

    private OrderVO toVO(Order order, List<OrderItem> items) {
        OrderVO vo = new OrderVO();
        // 复制订单字段，statusDesc 取 OrderStatus.descOf(order.getStatus())
        // items → List<OrderItemVO>，金额字段直接透传 BigDecimal
        return vo;
    }
```

- [ ] **Step 3 跑测试转绿；Step 4 提交**：`feat(order): 实现订单分页与详情查询`

### Task 8 状态流转（支付 / 取消 / 确认收货 / 发货）

**Files:** Modify `OrderServiceImpl.java`、`OrderServiceImplTest.java`

- [ ] **Step 1 补失败测试**：

```java
    @Test
    void pay_should_move_pending_payment_to_pending_shipment();  // 断言 status=2 且 payTime 非空
    @Test
    void pay_should_fail_when_status_is_not_pending_payment();   // 4006
    @Test
    void cancel_should_restore_stock_and_decrease_sales();       // verify restoreStock / decreaseSales
    @Test
    void cancel_should_fail_when_order_already_paid();           // 4006
    @Test
    void confirm_should_move_pending_receipt_to_finished();      // status=4
    @Test
    void ship_should_move_pending_shipment_to_pending_receipt(); // status=3
```

- [ ] **Step 2 实现**：

```java
    /**
     * 取出本人订单并校验期望状态，非法流转统一抛 4006
     */
    private Order requireOrder(Long userId, String orderNo, OrderStatus expect) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!expect.getCode().equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        return order;
    }
```

  - `pay`：`requireOrder(userId, orderNo, PENDING_PAYMENT)` → `status = 2`、`payTime = now` → `updateById`
  - `cancel`：`@Transactional`，`requireOrder(..., PENDING_PAYMENT)` → 查明细逐条 `restoreStock` + `decreaseSales` → `status = 5`、`cancelTime = now`
  - `confirm`：`requireOrder(..., PENDING_RECEIPT)` → `status = 4`、`finishTime = now`
  - `ship`：查 `orderNo` 且 `status = 2`（不存在抛 4005，状态不符抛 4006）→ `status = 3`、`shipTime = now`

- [ ] **Step 3 跑测试转绿；Step 4 提交**：`feat(order): 实现订单状态流转（支付/取消/确认收货/发货）`

### Task 9 后台订单接口与权限登记

**Files:** Create `controller/admin/AdminOrderController.java`；Modify `AdminDataInitializer.java`

- [ ] Controller：

```java
@Tag(name = "订单管理（后台）", description = "管理后台订单相关接口")
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "订单分页（可按订单号/状态筛选）")
    @GetMapping
    @PreAuthorize("hasAuthority('order:order:list')")
    public Result<PageResult<OrderVO>> page(@Valid AdminOrderPageQuery query) {
        return Result.success(orderService.pageForAdmin(query));
    }

    @Operation(summary = "发货")
    @PostMapping("/{orderNo}/ship")
    @PreAuthorize("hasAuthority('order:order:ship')")
    @OperationLog(module = "订单管理", type = "发货")
    public Result<Void> ship(@PathVariable String orderNo) {
        orderService.ship(orderNo);
        return Result.success();
    }
}
```

- [ ] `AdminDataInitializer#buildMenus` 末尾（`return menus;` 之前）追加：

```java
        Menu order = insertMenu(menuMapper, "订单管理", 1, 0L, "/order", "order", "List", 3, menus);
        Menu orderList = insertMenu(menuMapper, "订单列表", 2, order.getId(), "/order/list", "order:order:list", null, 1, menus);
        insertMenu(menuMapper, "订单发货", 3, orderList.getId(), null, "order:order:ship", null, 1, menus);
```

- [ ] ⚠️ **注意**：`AdminDataInitializer` 只在 `admin_user` 为空时执行，**已初始化过的本地库不会自动补菜单**。本地可选：`DROP DATABASE spring_shop` 重建后重启，或登录后台用「菜单管理」手动补「订单管理 / 订单列表 / 订单发货」三个节点
- [ ] 验证：`mvn -q -pl spring-shop-admin,spring-shop-order -am compile`
- [ ] 提交：`feat(order): 新增后台订单接口与菜单权限登记`

### Task 10 前台接口层与集成测试

**Files:** Create `controller/OrderController.java`、`controller/AddressController.java`、`spring-shop-web/src/test/java/com/springshop/web/OrderIntegrationTest.java`

- [ ] 两个 Controller 照抄 `CartController` 风格（`@Tag` / `@Operation` / `@Valid` / `UserContext.getUserId()` / 统一 `Result<T>`），路径见「二、接口清单」
- [ ] 集成测试类头**必须同时具备 4 项 + Redis Mock**（与 CartIntegrationTest 一致）：

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderIntegrationTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpRedisMocks() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }
}
```

- [ ] 用例（H2 走 Flyway `db/migration-test`，无本地依赖）：

```java
    @Test void orders_should_require_login();                         // GET /api/orders → 401
    @Test void address_crud_should_work_and_keep_single_default();
    @Test void create_order_with_empty_cart_should_return_4002();
    @Test void create_order_should_deduct_stock_and_clear_checked_items();
    @Test void create_order_should_return_4004_and_rollback_stock_when_short();  // 断言库存未变
    @Test void create_order_with_others_address_should_return_4001();
    @Test void pay_then_ship_then_confirm_should_follow_status_flow();           // 支付→后台发货→确认收货
    @Test void cancel_should_restore_stock_and_keep_order();
    @Test void detail_of_others_order_should_return_4005();
    @Test void admin_orders_should_reject_front_user_token();                    // 前台 token 访问 /api/admin/orders → 401/403
```

- [ ] 关键断言样例（下单成功）：

```java
    JsonNode data = readJson(mockMvc.perform(post("/api/orders")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("addressId", addressId, "remark", "尽快发货"))))
            .andExpect(status().isOk())
            .andReturn()).get("data");
    String orderNo = data.asText();
    assertEquals(9, productSkuMapper.selectById(skuId).getStock());   // 初始 10，买 1
    assertEquals(0, cartItems(token).size());                        // 勾选条目已清理
```

- [ ] 验证：`mvn -pl spring-shop-web -am test -Dtest=OrderIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] 提交：`feat(order): 新增订单与地址接口及集成测试`

### Task 11 收尾

**Files:** Create `docs/order-module.md`；Modify `README.md`

- [ ] 跑 `mvn clean verify`，确认 `BUILD SUCCESS`（CI 等价命令，含既有 Auth/Admin/Product/Cart 全部回归）
- [ ] `docs/order-module.md` 记录：下单时序（校验→扣库存→写订单→清购物车）、库存条件扣减方案与并发取舍、状态机、快照设计、后台权限登记
- [ ] `README.md`：模块划分补 `spring-shop-order` 一行；业务规划表订单模块状态改为 ✅
- [ ] 提交：`docs(order): 补充订单模块技术梳理与 README 状态`

## 五、验证要点

```bash
mvn -pl spring-shop-order -am test -Dsurefire.failIfNoSpecifiedTests=false              # 模块单测
mvn -pl spring-shop-web -am test -Dtest=OrderIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn clean verify                                                                       # 等价 CI
```

- 单测需 `@BeforeAll` 预热 `Order / OrderItem / ShippingAddress / CartItem / Product / ProductSku` 的 `TableInfoHelper`
- 集成测试类头 4 件套 + `@MockBean StringRedisTemplate`，缺一 CI 必挂
- 无需改 `SecurityConfig`（订单/地址接口默认 `authenticated()`），无需新增 Flyway 脚本

## 六、暂不做（YAGNI，留待后续迭代）

- 真实支付网关与异步回调、退款（状态 6 已预留）
- 待付款超时自动取消（`@Scheduled` 定时任务）、订单超时提醒
- 「立即购买」直接下单（不经过购物车）、优惠券/运费/积分抵扣
- 物流轨迹、发票、订单导出、加购并发去重（唯一键冲突兜底）
