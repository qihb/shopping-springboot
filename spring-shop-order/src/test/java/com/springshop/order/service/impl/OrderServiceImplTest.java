package com.springshop.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.cart.entity.CartItem;
import com.springshop.cart.mapper.CartItemMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.order.dto.AdminOrderPageQuery;
import com.springshop.order.dto.OrderCreateRequest;
import com.springshop.order.dto.OrderPageQuery;
import com.springshop.order.entity.Order;
import com.springshop.order.entity.OrderItem;
import com.springshop.order.entity.ShippingAddress;
import com.springshop.order.enums.OrderStatus;
import com.springshop.order.mapper.OrderItemMapper;
import com.springshop.order.mapper.OrderMapper;
import com.springshop.order.mapper.ShippingAddressMapper;
import com.springshop.order.vo.OrderVO;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import com.springshop.product.product.service.InventoryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单服务单元测试（纯 Mockito，不启动 Spring 容器）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceImplTest {

    private static final Long USER_ID = 1L;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderItemMapper orderItemMapper;

    @Mock
    private ShippingAddressMapper shippingAddressMapper;

    @Mock
    private CartItemMapper cartItemMapper;

    @Mock
    private ProductSkuMapper productSkuMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeAll
    static void warmupMybatisPlusLambdaCache() {
        Class<?>[] entities = new Class<?>[] { Order.class, OrderItem.class, ShippingAddress.class,
                CartItem.class, Product.class, ProductSku.class };
        for (Class<?> entityClass : entities) {
            try {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""), entityClass);
            } catch (Exception ignore) {
                // ignore: 预热失败时由真实运行环境再初始化，单测仅尽力而为
            }
        }
    }

    // ---------------- 下单 ----------------

    @Test
    void create_should_fail_when_address_not_owned() {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, 999L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, createRequest(5L)));
        assertEquals(ResultCode.ORDER_ADDRESS_NOT_FOUND.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void create_should_fail_when_no_checked_cart_item() {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, USER_ID));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, createRequest(5L)));
        assertEquals(ResultCode.ORDER_CART_EMPTY.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void create_should_fail_when_cart_item_sku_missing() {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, USER_ID));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cartItem(1L, 10L, 2)));
        // SKU 已被删除 → 批量查询结果为空
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, createRequest(5L)));
        assertEquals(ResultCode.PRODUCT_SKU_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void create_should_fail_when_product_off_shelf() {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, USER_ID));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cartItem(1L, 10L, 2)));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sku(10L, 100L, 1, "10.00")));
        when(productMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(product(100L, 0)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, createRequest(5L)));
        assertEquals(ResultCode.ORDER_SKU_UNAVAILABLE.getCode(), ex.getCode());
        verify(inventoryService, never()).lock(any(), anyInt(), any());
    }

    @Test
    void create_should_fail_when_inventory_insufficient() {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, USER_ID));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cartItem(1L, 10L, 2)));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sku(10L, 100L, 1, "10.00")));
        when(productMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(product(100L, 1)));
        // 库存域抛「可售量不足」，订单域必须把它翻译成对外的 4004
        doThrow(new BusinessException(ResultCode.PRODUCT_INVENTORY_INSUFFICIENT))
                .when(inventoryService).lock(eq(10L), eq(2), any());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, createRequest(5L)));
        assertEquals(ResultCode.ORDER_STOCK_INSUFFICIENT.getCode(), ex.getCode(),
                "库存域错误码必须映射为订单域的 4004，否则前端契约被破坏");
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void create_should_map_missing_inventory_row_to_stock_insufficient() {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, USER_ID));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cartItem(1L, 10L, 2)));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sku(10L, 100L, 1, "10.00")));
        when(productMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(product(100L, 1)));
        // 库存行缺失（历史脏数据）也归到 4004，不向前台泄漏内部状态
        doThrow(new BusinessException(ResultCode.PRODUCT_INVENTORY_NOT_FOUND))
                .when(inventoryService).lock(eq(10L), eq(2), any());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, createRequest(5L)));
        assertEquals(ResultCode.ORDER_STOCK_INSUFFICIENT.getCode(), ex.getCode());
    }

    @Test
    void create_should_insert_order_and_items_with_snapshot() {
        stubSuccessfulCreate(List.of(cartItem(1L, 10L, 2)));
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            Order entity = invocation.getArgument(0);
            entity.setId(1000L);
            return 1;
        });

        String orderNo = orderService.create(USER_ID, createRequest(5L));

        // 订单主表快照
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertNotNull(orderNo);
        assertEquals(23, orderNo.length());
        assertEquals(orderNo, saved.getOrderNo());

        // 库存只做「锁定」：可售挪到锁定，业务单号用订单号（便于按订单对账流水）
        verify(inventoryService).lock(10L, 2, orderNo);
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(OrderStatus.PENDING_PAYMENT.getCode(), saved.getStatus());
        assertEquals(0, new BigDecimal("20.00").compareTo(saved.getTotalAmount()));
        assertEquals(0, saved.getTotalAmount().compareTo(saved.getPayAmount()));
        assertEquals("张三", saved.getReceiverName());
        assertEquals("13800138000", saved.getReceiverPhone());
        assertEquals("广东省深圳市南山区科技园路 1 号", saved.getReceiverAddress());
        assertEquals("尽快发货", saved.getRemark());

        // 明细快照
        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(itemCaptor.capture());
        OrderItem item = itemCaptor.getValue();
        assertEquals(1000L, item.getOrderId());
        assertEquals(100L, item.getProductId());
        assertEquals(10L, item.getSkuId());
        assertEquals("测试商品", item.getProductName());
        assertEquals("规格10", item.getSkuSpecs());
        assertEquals("url-100", item.getProductImage());
        assertEquals(0, new BigDecimal("10.00").compareTo(item.getPrice()));
        assertEquals(2, item.getQuantity());
        assertEquals(0, new BigDecimal("20.00").compareTo(item.getSubtotal()));

        // 下单成功后清理购物车勾选项
        verify(cartItemMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_should_delete_only_checked_cart_items() {
        stubSuccessfulCreate(List.of(cartItem(1L, 10L, 2), cartItem(2L, 11L, 1)));

        orderService.create(USER_ID, createRequest(5L));

        ArgumentCaptor<LambdaQueryWrapper<CartItem>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(cartItemMapper).delete(captor.capture());
        LambdaQueryWrapper<CartItem> wrapper = captor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        // 仅按本次勾选条目 id 批量删除（IN 子句），不按 checked 条件整片删除
        assertTrue(sqlSegment.toUpperCase().contains("IN"), "SQL 片段应包含 IN：" + sqlSegment);
        // 1 个 userId 占位符 + 2 个条目 id 占位符
        assertEquals(3, wrapper.getParamNameValuePairs().size());
    }

    @Test
    void create_should_evict_cart_cache() {
        stubSuccessfulCreate(List.of(cartItem(1L, 10L, 2)));

        orderService.create(USER_ID, createRequest(5L));

        // 下单会把购物车勾选行物理删除，缓存必须同步失效。
        // 否则 GET /api/cart 会在 7 天滑动 TTL 内一直返回已下单的条目（脏读），
        // 前端表现为「下单后购物车没清空、仍是勾选态」，点结算才报「没有勾选商品」。
        verify(stringRedisTemplate).delete(RedisKeys.cart(USER_ID));
    }

    // ---------------- 查询 ----------------

    @Test
    void pageMine_should_filter_by_user_and_status() {
        OrderPageQuery query = new OrderPageQuery();
        query.setStatus(OrderStatus.PENDING_PAYMENT.getCode());

        Order order = order(1000L, OrderStatus.PENDING_PAYMENT.getCode());
        Page<Order> page = new Page<>(1L, 10L);
        page.setRecords(List.of(order));
        page.setTotal(1L);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(orderItem(1000L, 10L, 100L, 2)));

        PageResult<OrderVO> result = orderService.pageMine(USER_ID, query);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        OrderVO vo = result.getRecords().get(0);
        assertEquals(1000L, vo.getId());
        assertEquals(OrderStatus.PENDING_PAYMENT.getCode(), vo.getStatus());
        assertEquals("待付款", vo.getStatusDesc());
        assertEquals(0, new BigDecimal("20.00").compareTo(vo.getPayAmount()));
        assertEquals(1, vo.getItems().size());
        assertEquals("测试商品", vo.getItems().get(0).getProductName());
    }

    @Test
    void detail_should_fail_when_order_not_found() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.detail(USER_ID, "NO-OTHER"));
        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void detail_should_return_items_with_snapshot() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_SHIPMENT.getCode()));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(orderItem(1000L, 10L, 100L, 2)));

        OrderVO vo = orderService.detail(USER_ID, "NO1000");

        assertEquals("NO1000", vo.getOrderNo());
        assertEquals("待发货", vo.getStatusDesc());
        assertEquals("张三", vo.getReceiverName());
        assertEquals("广东省深圳市南山区科技园路 1 号", vo.getReceiverAddress());
        assertEquals(1, vo.getItems().size());
        assertEquals("规格10", vo.getItems().get(0).getSkuSpecs());
        assertEquals(0, new BigDecimal("20.00").compareTo(vo.getItems().get(0).getSubtotal()));
    }

    @Test
    void pageForAdmin_should_not_filter_by_user() {
        AdminOrderPageQuery query = new AdminOrderPageQuery();
        query.setOrderNo("2026");
        Order other = order(2000L, OrderStatus.PENDING_SHIPMENT.getCode());
        other.setUserId(999L);
        Page<Order> page = new Page<>(1L, 10L);
        page.setRecords(List.of(other));
        page.setTotal(1L);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        PageResult<OrderVO> result = orderService.pageForAdmin(query);

        assertEquals(1, result.getRecords().size());
        assertEquals(2000L, result.getRecords().get(0).getId());
    }

    // ---------------- 状态流转 ----------------

    @Test
    void pay_should_move_to_pending_shipment_and_outbound() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));
        when(orderMapper.markPaid(eq("NO1000"), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.PENDING_SHIPMENT.getCode()), any(LocalDateTime.class))).thenReturn(1);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                orderItem(1000L, 10L, 100L, 2),
                orderItem(1000L, 11L, 101L, 1)));

        orderService.pay(USER_ID, "NO1000");

        // 状态推进走条件更新而不是 updateById：并发重复支付只有一方能过
        ArgumentCaptor<LocalDateTime> payTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper).markPaid(eq("NO1000"), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.PENDING_SHIPMENT.getCode()), payTimeCaptor.capture());
        assertNotNull(payTimeCaptor.getValue());

        // 支付成功即出库：在库量与锁定量同时扣减
        verify(inventoryService).outbound(10L, 2, "NO1000");
        verify(inventoryService).outbound(11L, 1, "NO1000");
    }

    @Test
    void pay_should_fail_when_status_illegal() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_SHIPMENT.getCode()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(USER_ID, "NO1000"));
        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(orderMapper, never()).markPaid(any(), any(), any(), any());
        verify(inventoryService, never()).outbound(any(), anyInt(), any());
    }

    @Test
    void pay_should_not_outbound_when_conditional_update_loses() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));
        // 影响 0 行 = 另一方已支付或已取消，本请求不能再出库
        when(orderMapper.markPaid(eq("NO1000"), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.PENDING_SHIPMENT.getCode()), any(LocalDateTime.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(USER_ID, "NO1000"));
        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(inventoryService, never()).outbound(any(), anyInt(), any());
    }

    @Test
    void markPaid_should_return_true_and_outbound_when_update_hits() {
        // 条件更新命中：订单仍处于待付款状态，置为已付款（待发货）并记录支付时间
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));
        when(orderMapper.markPaid(eq("NO1000"), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.PENDING_SHIPMENT.getCode()), any(LocalDateTime.class))).thenReturn(1);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(orderItem(1000L, 10L, 100L, 2)));

        boolean paid = orderService.markPaid("NO1000");

        assertTrue(paid);
        ArgumentCaptor<LocalDateTime> payTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper).markPaid(eq("NO1000"), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.PENDING_SHIPMENT.getCode()), payTimeCaptor.capture());
        assertNotNull(payTimeCaptor.getValue());
        verify(inventoryService).outbound(10L, 2, "NO1000");
    }

    @Test
    void markPaid_should_return_false_when_update_misses() {
        // 并发落败：订单已被取消或已被支付，条件更新影响 0 行，返回 false 供调用方回滚
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));
        when(orderMapper.markPaid(eq("NO1000"), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.PENDING_SHIPMENT.getCode()), any(LocalDateTime.class))).thenReturn(0);

        boolean paid = orderService.markPaid("NO1000");

        assertFalse(paid);
        verify(inventoryService, never()).outbound(any(), anyInt(), any());
    }

    @Test
    void markPaid_should_return_false_when_order_missing() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertFalse(orderService.markPaid("NO-MISSING"));
        verify(orderMapper, never()).markPaid(any(), any(), any(), any());
    }

    @Test
    void cancel_should_release_lock_and_decrease_sales() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));
        when(orderMapper.cancelIfPendingPayment(eq(1000L), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.CANCELLED.getCode()), any(LocalDateTime.class))).thenReturn(1);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                orderItem(1000L, 10L, 100L, 2),
                orderItem(1000L, 11L, 101L, 1)));

        orderService.cancel(USER_ID, "NO1000");

        // 取消只释放锁定（锁定 → 可售），不动在库量
        verify(inventoryService).release(10L, 2, "NO1000");
        verify(inventoryService).release(11L, 1, "NO1000");
        verify(productMapper).decreaseSales(100L, 2);
        verify(productMapper).decreaseSales(101L, 1);

        // 状态推进与超时任务共用同一个条件更新，并发下只有一方能过
        ArgumentCaptor<LocalDateTime> cancelTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper).cancelIfPendingPayment(eq(1000L), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.CANCELLED.getCode()), cancelTimeCaptor.capture());
        assertNotNull(cancelTimeCaptor.getValue());
    }

    @Test
    void cancel_should_fail_and_not_release_when_conditional_update_loses() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));
        // 影响 0 行 = 另一方（超时任务或重复取消）已推进状态，本请求不能再释放锁定
        when(orderMapper.cancelIfPendingPayment(eq(1000L), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.CANCELLED.getCode()), any(LocalDateTime.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancel(USER_ID, "NO1000"));
        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(inventoryService, never()).release(any(), anyInt(), any());
    }

    // ---------------- 系统取消（超时自动取消） ----------------

    @Test
    void systemCancel_should_restore_stock_and_evict_cache_when_conditional_update_hits() {
        // 条件更新命中：订单仍处于待付款状态，置为已取消并返回影响行数 1
        when(orderMapper.cancelIfPendingPayment(eq(1000L), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.CANCELLED.getCode()), any(LocalDateTime.class))).thenReturn(1);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                orderItem(1000L, 10L, 100L, 2),
                orderItem(1000L, 11L, 101L, 1)));

        orderService.systemCancel(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));

        // 逐条释放锁定并回滚销量
        verify(inventoryService).release(10L, 2, "NO1000");
        verify(inventoryService).release(11L, 1, "NO1000");
        verify(productMapper).decreaseSales(100L, 2);
        verify(productMapper).decreaseSales(101L, 1);

        // 状态置为已取消（CANCELLED 入参）且取消时间非空
        ArgumentCaptor<LocalDateTime> cancelTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderMapper).cancelIfPendingPayment(eq(1000L), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.CANCELLED.getCode()), cancelTimeCaptor.capture());
        assertNotNull(cancelTimeCaptor.getValue());

        // 销量变更后主动失效涉及商品的详情缓存
        verify(stringRedisTemplate).delete(RedisKeys.productDetail(100L));
        verify(stringRedisTemplate).delete(RedisKeys.productDetail(101L));
    }

    @Test
    void systemCancel_should_skip_rollback_when_conditional_update_misses() {
        // 条件更新影响 0 行：订单已被用户取消或已支付，静默返回，不释放锁定与销量
        when(orderMapper.cancelIfPendingPayment(eq(1000L), eq(OrderStatus.PENDING_PAYMENT.getCode()),
                eq(OrderStatus.CANCELLED.getCode()), any(LocalDateTime.class))).thenReturn(0);

        orderService.systemCancel(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));

        verify(inventoryService, never()).release(any(), anyInt(), any());
        verify(productMapper, never()).decreaseSales(any(), any());
        verify(orderItemMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void confirm_should_move_to_finished() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_RECEIPT.getCode()));

        orderService.confirm(USER_ID, "NO1000");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderStatus.FINISHED.getCode(), captor.getValue().getStatus());
        assertNotNull(captor.getValue().getFinishTime());
    }

    @Test
    void ship_should_move_to_pending_receipt() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_SHIPMENT.getCode()));

        orderService.ship("NO1000");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderStatus.PENDING_RECEIPT.getCode(), captor.getValue().getStatus());
        assertNotNull(captor.getValue().getShipTime());
    }

    @Test
    void ship_should_fail_when_order_not_found() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.ship("NO-MISSING"));
        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    // ---------------- 构造辅助 ----------------

    private void stubSuccessfulCreate(List<CartItem> checkedItems) {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, USER_ID));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(checkedItems);
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                sku(10L, 100L, 1, "10.00"),
                sku(11L, 101L, 1, "10.00")));
        when(productMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                product(100L, 1),
                product(101L, 1)));
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            Order entity = invocation.getArgument(0);
            entity.setId(1000L);
            return 1;
        });
    }

    private OrderCreateRequest createRequest(Long addressId) {
        OrderCreateRequest request = new OrderCreateRequest();
        request.setAddressId(addressId);
        request.setRemark("尽快发货");
        return request;
    }

    private ShippingAddress address(Long id, Long userId) {
        ShippingAddress address = new ShippingAddress();
        address.setId(id);
        address.setUserId(userId);
        address.setReceiverName("张三");
        address.setReceiverPhone("13800138000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("科技园路 1 号");
        return address;
    }

    private CartItem cartItem(Long id, Long skuId, Integer quantity) {
        CartItem item = new CartItem();
        item.setId(id);
        item.setUserId(USER_ID);
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        item.setChecked(1);
        return item;
    }

    private ProductSku sku(Long id, Long productId, Integer status, String price) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setProductId(productId);
        sku.setStatus(status);
        sku.setPrice(new BigDecimal(price));
        sku.setSpecs("规格" + id);
        return sku;
    }

    private Product product(Long id, Integer status) {
        Product product = new Product();
        product.setId(id);
        product.setName("测试商品");
        product.setMainImage("url-" + id);
        product.setStatus(status);
        return product;
    }

    private Order order(Long id, Integer status) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo("NO" + id);
        order.setUserId(USER_ID);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("20.00"));
        order.setPayAmount(new BigDecimal("20.00"));
        order.setReceiverName("张三");
        order.setReceiverPhone("13800138000");
        order.setReceiverAddress("广东省深圳市南山区科技园路 1 号");
        return order;
    }

    private OrderItem orderItem(Long orderId, Long skuId, Long productId, Integer quantity) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setSkuId(skuId);
        item.setProductId(productId);
        item.setProductName("测试商品");
        item.setSkuSpecs("规格" + skuId);
        item.setProductImage("url-" + productId);
        item.setPrice(new BigDecimal("10.00"));
        item.setQuantity(quantity);
        item.setSubtotal(new BigDecimal("10.00").multiply(BigDecimal.valueOf(quantity)));
        return item;
    }
}
