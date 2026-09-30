package com.springshop.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.cart.entity.CartItem;
import com.springshop.cart.mapper.CartItemMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
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

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        verify(productSkuMapper, never()).deductStock(any(), any());
    }

    @Test
    void create_should_fail_when_deduct_stock_returns_zero() {
        when(shippingAddressMapper.selectById(5L)).thenReturn(address(5L, USER_ID));
        when(cartItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cartItem(1L, 10L, 2)));
        when(productSkuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sku(10L, 100L, 1, "10.00")));
        when(productMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(product(100L, 1)));
        when(productSkuMapper.deductStock(10L, 2)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, createRequest(5L)));
        assertEquals(ResultCode.ORDER_STOCK_INSUFFICIENT.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
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
    void pay_should_move_to_pending_shipment() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));

        orderService.pay(USER_ID, "NO1000");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderStatus.PENDING_SHIPMENT.getCode(), captor.getValue().getStatus());
        assertNotNull(captor.getValue().getPayTime());
    }

    @Test
    void pay_should_fail_when_status_illegal() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_SHIPMENT.getCode()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(USER_ID, "NO1000"));
        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void cancel_should_restore_stock_and_decrease_sales() {
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(order(1000L, OrderStatus.PENDING_PAYMENT.getCode()));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                orderItem(1000L, 10L, 100L, 2),
                orderItem(1000L, 11L, 101L, 1)));

        orderService.cancel(USER_ID, "NO1000");

        verify(productSkuMapper).restoreStock(10L, 2);
        verify(productSkuMapper).restoreStock(11L, 1);
        verify(productMapper).decreaseSales(100L, 2);
        verify(productMapper).decreaseSales(101L, 1);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderStatus.CANCELLED.getCode(), captor.getValue().getStatus());
        assertNotNull(captor.getValue().getCancelTime());
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
        when(productSkuMapper.deductStock(any(), any())).thenReturn(1);
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
