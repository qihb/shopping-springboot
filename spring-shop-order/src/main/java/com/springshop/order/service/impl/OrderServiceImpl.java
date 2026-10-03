package com.springshop.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.springshop.cart.entity.CartItem;
import com.springshop.cart.mapper.CartItemMapper;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.PageResult;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.order.dto.AdminOrderPageQuery;
import com.springshop.order.dto.OrderCreateRequest;
import com.springshop.order.dto.OrderExportQuery;
import com.springshop.order.dto.OrderPageQuery;
import com.springshop.order.entity.Order;
import com.springshop.order.entity.OrderItem;
import com.springshop.order.entity.ShippingAddress;
import com.springshop.order.enums.OrderStatus;
import com.springshop.order.mapper.OrderItemMapper;
import com.springshop.order.mapper.OrderMapper;
import com.springshop.order.mapper.ShippingAddressMapper;
import com.springshop.order.service.OrderService;
import com.springshop.order.vo.OrderItemVO;
import com.springshop.order.vo.OrderVO;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 *
 * <p>下单在同一事务内完成「校验 → 扣库存 → 写订单/明细 → 清购物车勾选项」，
 * 任一步失败（含库存不足）整体回滚；收货信息与商品信息均按下单时刻落库快照。
 *
 * <p>依赖购物车模块读取/清理勾选条目，依赖商品模块做只读校验与库存、销量增减。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final String ORDER_NO_PATTERN = "yyyyMMddHHmmssSSS";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShippingAddressMapper shippingAddressMapper;
    private final CartItemMapper cartItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public OrderServiceImpl(OrderMapper orderMapper,
                            OrderItemMapper orderItemMapper,
                            ShippingAddressMapper shippingAddressMapper,
                            CartItemMapper cartItemMapper,
                            ProductSkuMapper productSkuMapper,
                            ProductMapper productMapper,
                            StringRedisTemplate stringRedisTemplate) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.shippingAddressMapper = shippingAddressMapper;
        this.cartItemMapper = cartItemMapper;
        this.productSkuMapper = productSkuMapper;
        this.productMapper = productMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(Long userId, OrderCreateRequest request) {
        ShippingAddress address = shippingAddressMapper.selectById(request.getAddressId());
        if (address == null || !Objects.equals(address.getUserId(), userId)) {
            throw new BusinessException(ResultCode.ORDER_ADDRESS_NOT_FOUND);
        }

        // 下单来源固定为当前用户勾选项，未勾选任何商品不允许下单
        List<CartItem> checkedItems = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getChecked, 1)
                .orderByAsc(CartItem::getId));
        if (checkedItems.isEmpty()) {
            throw new BusinessException(ResultCode.ORDER_CART_EMPTY);
        }

        // 批量查 SKU 与商品，避免逐条查询产生 N+1
        Map<Long, ProductSku> skuMap = loadSkus(checkedItems);
        Map<Long, Product> productMap = loadProducts(skuMap.values());

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem cartItem : checkedItems) {
            ProductSku sku = requireOnSaleSku(cartItem.getSkuId(), skuMap, productMap);
            int quantity = cartItem.getQuantity();
            // 条件扣减：库存不足影响 0 行 → 抛异常，整个事务回滚（已扣库存一并回滚）
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
        // 暂无优惠券/运费，实付与应付相等（YAGNI）
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

        // 与订单同事务：下单失败则购物车勾选项保持原样，仅删除本次下单的条目
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .in(CartItem::getId, checkedItems.stream().map(CartItem::getId).toList()));

        // 下单成功（提交前）主动失效涉及商品的详情缓存：销量已变，防止详情页在缓存 TTL 内读到旧销量
        evictProductDetailCache(orderItems);

        return order.getOrderNo();
    }

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

    @Override
    public OrderVO detail(Long userId, String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getUserId, userId));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        return toVO(order, items);
    }

    @Override
    public Order getByOrderNo(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
    }

    @Override
    public void pay(Long userId, String orderNo) {
        Order order = requireOrder(userId, orderNo, OrderStatus.PENDING_PAYMENT);
        order.setStatus(OrderStatus.PENDING_SHIPMENT.getCode());
        order.setPayTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    @Override
    public boolean markPaid(String orderNo) {
        // 条件更新（status=1 才生效）与超时取消任务天然互斥，影响 0 行即并发落败
        return orderMapper.markPaid(orderNo, OrderStatus.PENDING_PAYMENT.getCode(),
                OrderStatus.PENDING_SHIPMENT.getCode(), LocalDateTime.now()) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, String orderNo) {
        Order order = requireOrder(userId, orderNo, OrderStatus.PENDING_PAYMENT);
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        // 取消即回滚库存与销量，与订单状态变更同一事务
        rollbackStockAndSales(items);
        order.setStatus(OrderStatus.CANCELLED.getCode());
        order.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(order);
        // 取消后主动失效涉及商品的详情缓存
        evictProductDetailCache(items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void systemCancel(Order order) {
        // 条件更新保证并发安全：仅当订单仍为待付款时置为已取消；
        // 影响 0 行说明订单已被用户取消或已支付，静默返回（不抛错、不回滚库存）
        int updated = orderMapper.cancelIfPendingPayment(order.getId(),
                OrderStatus.PENDING_PAYMENT.getCode(), OrderStatus.CANCELLED.getCode(), LocalDateTime.now());
        if (updated == 0) {
            return;
        }
        // 条件更新生效后才回滚库存与销量，与状态变更同一事务，任一步失败整体回滚
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        rollbackStockAndSales(items);
        // 取消后主动失效涉及商品的详情缓存
        evictProductDetailCache(items);
    }

    @Override
    public void confirm(Long userId, String orderNo) {
        Order order = requireOrder(userId, orderNo, OrderStatus.PENDING_RECEIPT);
        order.setStatus(OrderStatus.FINISHED.getCode());
        order.setFinishTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    @Override
    public PageResult<OrderVO> pageForAdmin(AdminOrderPageQuery query) {
        // 后台不过滤用户，可按订单号模糊 + 状态筛选
        Page<Order> page = orderMapper.selectPage(query.toPage(), new LambdaQueryWrapper<Order>()
                .like(StringUtils.hasText(query.getOrderNo()), Order::getOrderNo, query.getOrderNo())
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

    @Override
    public List<OrderVO> exportPage(OrderExportQuery query, long current, long pageSize) {
        OrderExportQuery safeQuery = query == null ? new OrderExportQuery() : query;
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        if (safeQuery.getIds() != null && !safeQuery.getIds().isEmpty()) {
            // 「导出选中」优先于其他条件：用户勾了行就是明确的意图
            wrapper.in(Order::getId, safeQuery.getIds());
        } else {
            wrapper.like(StringUtils.hasText(safeQuery.getOrderNo()), Order::getOrderNo, safeQuery.getOrderNo())
                    .eq(safeQuery.getStatus() != null, Order::getStatus, safeQuery.getStatus());
        }
        // 与列表页同序（id 倒序），保证分批翻页时不会因为顺序漂移而漏行/重复
        wrapper.orderByDesc(Order::getId);
        // searchCount=false：导出不展示总页数，省掉每页一次 COUNT
        Page<Order> page = orderMapper.selectPage(new Page<>(current, pageSize, false), wrapper);

        Map<Long, List<OrderItem>> itemMap = loadItems(page.getRecords());
        return page.getRecords().stream()
                .map(order -> toVO(order, itemMap.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    @Override
    public void ship(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.PENDING_SHIPMENT.getCode().equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        order.setStatus(OrderStatus.PENDING_RECEIPT.getCode());
        order.setShipTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    /**
     * 回滚订单库存与销量：按明细逐条恢复 SKU 库存、扣减商品销量（用户取消与系统取消共用）
     */
    private void rollbackStockAndSales(List<OrderItem> items) {
        for (OrderItem item : items) {
            productSkuMapper.restoreStock(item.getSkuId(), item.getQuantity());
            productMapper.decreaseSales(item.getProductId(), item.getQuantity());
        }
    }

    /**
     * 主动失效商品详情缓存：按明细涉及的 productId 去重后逐个删除详情 key，
     * 防止详情页在 30 分钟缓存 TTL 内读到过期的销量等数据
     */
    private void evictProductDetailCache(List<OrderItem> items) {
        items.stream()
                .map(OrderItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(productId -> stringRedisTemplate.delete(RedisKeys.productDetail(productId)));
    }

    /**
     * 取出本人订单并校验期望状态，订单不存在或越权抛 4005，非法流转抛 4006
     */
    private Order requireOrder(Long userId, String orderNo, OrderStatus expect) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo)
                .eq(Order::getUserId, userId));
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!expect.getCode().equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        return order;
    }

    /**
     * 校验 SKU 可购买：SKU 存在（2011）、规格在售、所属商品存在且在售（4003），返回 SKU
     */
    private ProductSku requireOnSaleSku(Long skuId, Map<Long, ProductSku> skuMap, Map<Long, Product> productMap) {
        ProductSku sku = skuMap.get(skuId);
        if (sku == null) {
            throw new BusinessException(ResultCode.PRODUCT_SKU_NOT_FOUND);
        }
        if (!Objects.equals(sku.getStatus(), 1)) {
            throw new BusinessException(ResultCode.ORDER_SKU_UNAVAILABLE);
        }
        Product product = productMap.get(sku.getProductId());
        if (product == null || !Objects.equals(product.getStatus(), 1)) {
            throw new BusinessException(ResultCode.ORDER_SKU_UNAVAILABLE);
        }
        return sku;
    }

    private OrderItem buildOrderItem(ProductSku sku, Product product, int quantity, BigDecimal subtotal) {
        OrderItem item = new OrderItem();
        item.setProductId(sku.getProductId());
        item.setSkuId(sku.getId());
        item.setProductName(product.getName());
        item.setSkuSpecs(sku.getSpecs());
        item.setProductImage(product.getMainImage());
        item.setPrice(sku.getPrice());
        item.setQuantity(quantity);
        item.setSubtotal(subtotal);
        return item;
    }

    private Map<Long, ProductSku> loadSkus(List<CartItem> items) {
        Set<Long> skuIds = items.stream()
                .map(CartItem::getSkuId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                        .in(ProductSku::getId, skuIds))
                .stream()
                .collect(Collectors.toMap(ProductSku::getId, sku -> sku));
    }

    private Map<Long, Product> loadProducts(Collection<ProductSku> skus) {
        Set<Long> productIds = skus.stream()
                .map(ProductSku::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .in(Product::getId, productIds))
                .stream()
                .collect(Collectors.toMap(Product::getId, product -> product));
    }

    /**
     * 明细批量查询：order_id in (...) 一次性取出，按 orderId 分组，避免 N+1
     */
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

    /**
     * 订单号：时间戳（毫秒）+ 用户 id 后 3 位 + 3 位随机数
     *
     * <p>不做查重重试，极低概率冲突由 orders 表的唯一键 uk_order_no 兜底。
     */
    private String generateOrderNo(Long userId) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern(ORDER_NO_PATTERN));
        String userSuffix = String.format("%03d", Math.abs(userId) % 1000);
        String random = String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
        return timestamp + userSuffix + random;
    }

    private String joinAddress(ShippingAddress address) {
        return address.getProvince() + address.getCity() + address.getDistrict() + address.getDetailAddress();
    }

    private OrderVO toVO(Order order, List<OrderItem> items) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setStatus(order.getStatus());
        vo.setStatusDesc(OrderStatus.descOf(order.getStatus()));
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverAddress(order.getReceiverAddress());
        vo.setRemark(order.getRemark());
        vo.setCreateTime(order.getCreateTime());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setFinishTime(order.getFinishTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setItems(items.stream().map(this::toItemVO).toList());
        return vo;
    }

    private OrderItemVO toItemVO(OrderItem item) {
        OrderItemVO vo = new OrderItemVO();
        vo.setProductId(item.getProductId());
        vo.setSkuId(item.getSkuId());
        vo.setProductName(item.getProductName());
        vo.setSkuSpecs(item.getSkuSpecs());
        vo.setProductImage(item.getProductImage());
        vo.setPrice(item.getPrice());
        vo.setQuantity(item.getQuantity());
        vo.setSubtotal(item.getSubtotal());
        return vo;
    }
}
