package com.springshop.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.springshop.cart.dto.CartAddRequest;
import com.springshop.cart.dto.CartCheckedRequest;
import com.springshop.cart.dto.CartQuantityRequest;
import com.springshop.cart.entity.CartItem;
import com.springshop.cart.mapper.CartItemMapper;
import com.springshop.cart.service.CartService;
import com.springshop.cart.vo.CartItemVO;
import com.springshop.cart.vo.CartVO;
import com.springshop.common.exception.BusinessException;
import com.springshop.common.result.ResultCode;
import com.springshop.common.security.RedisKeys;
import com.springshop.product.product.entity.Product;
import com.springshop.product.product.entity.ProductSku;
import com.springshop.product.product.mapper.ProductMapper;
import com.springshop.product.product.mapper.ProductSkuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 购物车服务实现
 *
 * <p>依赖商品模块的 SKU/商品 Mapper 做只读校验与详情聚合，不反向写入商品数据。
 *
 * <p><b>缓存设计（Redis 读加速，DB 为主存）</b>：
 * <ul>
 * <li>结构：Redis Hash {@code cart:{userId}}，field=skuId，value={@code 条目id|quantity|checked}（checked 为 0/1）。
 *       value 中携带购物车条目 id：列表 VO 的 id 是前端改数量/勾选/删除的操作句柄，缓存命中路径
 *       不查购物车表，条目 id 必须随缓存一起存储；</li>
 * <li>TTL：7 天滑动过期，每次写操作与读回填后 expire；</li>
 * <li>写路径（加购/改数量/勾选/删除/清空）：先完成 DB 写，成功后再同步 Redis 对应 field
 *       （hset/hdel；批量删除类操作整 key 失效，下次读全量重建）。Redis 操作独立 try/catch，
 *       失败仅 log.warn 不影响主流程，由 DB 主存兜底；</li>
 * <li>读路径（列表）：先 HGETALL，命中且非空则仅批量查商品信息组装 VO（购物车表零查询）；
 *       miss（空 map 或任一 entry 解析失败）回源 DB 查完整购物车，并整 cart 重建缓存。</li>
 * </ul>
 */
@Service
public class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    /** 购物车缓存 TTL（天），滑动过期：每次写操作与读回填后续期 */
    private static final long CART_CACHE_DAYS = 7;

    private static final String REASON_SKU_LOST = "商品已失效";

    private static final String REASON_SKU_DISABLED = "该规格已停售";

    private static final String REASON_PRODUCT_OFF_SHELF = "商品已下架";

    private final CartItemMapper cartItemMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public CartServiceImpl(CartItemMapper cartItemMapper,
                           ProductSkuMapper productSkuMapper,
                           ProductMapper productMapper,
                           StringRedisTemplate stringRedisTemplate) {
        this.cartItemMapper = cartItemMapper;
        this.productSkuMapper = productSkuMapper;
        this.productMapper = productMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void add(Long userId, CartAddRequest request) {
        validateQuantity(request.getQuantity());

        ProductSku sku = requireOnSaleSku(request.getSkuId());

        CartItem existing = findItem(userId, request.getSkuId());
        if (existing == null) {
            if (sku.getStock() != null && request.getQuantity() > sku.getStock()) {
                throw new BusinessException(ResultCode.CART_STOCK_INSUFFICIENT);
            }
            CartItem item = new CartItem();
            item.setUserId(userId);
            item.setSkuId(request.getSkuId());
            item.setQuantity(request.getQuantity());
            item.setChecked(1);
            try {
                cartItemMapper.insert(item);
                // insert 已由 MyBatis-Plus 回填自增主键，DB 成功后同步对应缓存 field
                syncCartField(userId, item);
                return;
            } catch (DuplicateKeyException e) {
                // 并发窗口：查询与插入之间另一请求已写入同一 SKU，退化为累加，避免唯一键冲突冒泡为系统异常
                existing = findItem(userId, request.getSkuId());
                if (existing == null) {
                    throw e;
                }
            }
        }

        // 同 SKU 已存在则累加，并按加购语义自动勾选
        int targetQuantity = request.getQuantity() + existing.getQuantity();
        if (sku.getStock() != null && targetQuantity > sku.getStock()) {
            throw new BusinessException(ResultCode.CART_STOCK_INSUFFICIENT);
        }
        existing.setQuantity(targetQuantity);
        existing.setChecked(1);
        cartItemMapper.updateById(existing);
        syncCartField(userId, existing);
    }

    @Override
    public CartVO list(Long userId) {
        return assembleCartVO(readCartItems(userId));
    }

    /**
     * 读取购物车条目：优先 Redis Hash（读加速，购物车表零查询）；miss 时回源 DB 并整 cart 重建缓存
     */
    private List<CartItem> readCartItems(Long userId) {
        String cacheKey = RedisKeys.cart(userId);
        Map<Object, Object> cached = Collections.emptyMap();
        try {
            cached = stringRedisTemplate.opsForHash().entries(cacheKey);
        } catch (Exception e) {
            // Redis 读故障视为 miss，静默降级回源 DB
            log.warn("读取购物车缓存失败，回源 DB，userId={}", userId, e);
        }
        if (!cached.isEmpty()) {
            List<CartItem> parsedItems = parseCachedItems(cached);
            if (parsedItems != null) {
                return parsedItems;
            }
            // 任一 entry 解析失败视为缓存损坏，走 miss 重建
        }

        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .orderByDesc(CartItem::getId));
        rebuildCartCache(userId, items);
        return items;
    }

    /**
     * 将 Redis Hash 还原为购物车条目，按条目 id 倒序对齐 DB 列表的展示顺序；
     * 任一 entry 不合法返回 null，由调用方整体回源重建
     */
    private List<CartItem> parseCachedItems(Map<Object, Object> entries) {
        List<CartItem> items = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String[] parts = String.valueOf(entry.getValue()).split("\\|");
            if (parts.length != 3) {
                return null;
            }
            try {
                CartItem item = new CartItem();
                item.setId(Long.parseLong(parts[0]));
                item.setSkuId(Long.parseLong(String.valueOf(entry.getKey())));
                item.setQuantity(Integer.parseInt(parts[1]));
                item.setChecked(Integer.parseInt(parts[2]));
                items.add(item);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        items.sort(Comparator.comparing(CartItem::getId).reversed());
        return items;
    }

    /**
     * 整 cart 重建缓存并滑动续期；条目为空时不写（空购物车无需缓存，由 miss 回源兜底）
     */
    private void rebuildCartCache(Long userId, List<CartItem> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<String, String> cache = new HashMap<>(items.size());
        for (CartItem item : items) {
            cache.put(String.valueOf(item.getSkuId()), cartFieldValue(item));
        }
        syncCartFields(userId, cache);
    }

    /**
     * 缓存 value 编码：{@code 条目id|quantity|checked}
     */
    private String cartFieldValue(CartItem item) {
        return item.getId() + "|" + item.getQuantity() + "|" + item.getChecked();
    }

    /**
     * 单条 DB 写成功后同步对应缓存 field（hset + 滑动续期），Redis 故障静默降级不影响主流程
     */
    private void syncCartField(Long userId, CartItem item) {
        Map<String, String> cache = new HashMap<>(1);
        cache.put(String.valueOf(item.getSkuId()), cartFieldValue(item));
        syncCartFields(userId, cache);
    }

    /**
     * 批量同步缓存 field（putAll + 滑动续期），Redis 故障静默降级不影响主流程
     */
    private void syncCartFields(Long userId, Map<String, String> cache) {
        try {
            String cacheKey = RedisKeys.cart(userId);
            stringRedisTemplate.opsForHash().putAll(cacheKey, cache);
            stringRedisTemplate.expire(cacheKey, Duration.ofDays(CART_CACHE_DAYS));
        } catch (Exception e) {
            log.warn("同步购物车缓存失败，userId={}", userId, e);
        }
    }

    /**
     * DB 删除成功后移除对应缓存 field（hdel + 滑动续期），Redis 故障静默降级不影响主流程
     */
    private void removeCartField(Long userId, Long skuId) {
        try {
            String cacheKey = RedisKeys.cart(userId);
            stringRedisTemplate.opsForHash().delete(cacheKey, String.valueOf(skuId));
            stringRedisTemplate.expire(cacheKey, Duration.ofDays(CART_CACHE_DAYS));
        } catch (Exception e) {
            log.warn("删除购物车缓存 field 失败，userId={}, skuId={}", userId, skuId, e);
        }
    }

    /**
     * 整 key 失效购物车缓存（批量删除类写操作使用），下次读回源全量重建；Redis 故障静默降级
     */
    private void evictCartCache(Long userId) {
        try {
            stringRedisTemplate.delete(RedisKeys.cart(userId));
        } catch (Exception e) {
            log.warn("失效购物车缓存失败，userId={}", userId, e);
        }
    }

    /**
     * 组装购物车 VO：批量查 SKU 与商品补全展示信息，失效条目标记并汇总结算金额
     */
    private CartVO assembleCartVO(List<CartItem> items) {
        CartVO cartVO = new CartVO();
        List<CartItemVO> itemVOs = new ArrayList<>();
        cartVO.setItems(itemVOs);

        if (items.isEmpty()) {
            cartVO.setTotalQuantity(0);
            cartVO.setCheckedQuantity(0);
            cartVO.setCheckedAmount(BigDecimal.ZERO);
            return cartVO;
        }

        // 批量查 SKU 与商品，避免逐条查询产生 N+1
        Map<Long, ProductSku> skuMap = collectSkus(items);
        Map<Long, Product> productMap = collectProducts(skuMap.values());

        int totalQuantity = 0;
        int checkedQuantity = 0;
        BigDecimal checkedAmount = BigDecimal.ZERO;
        for (CartItem item : items) {
            CartItemVO itemVO = buildItemVO(item, skuMap, productMap);
            itemVOs.add(itemVO);

            int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
            totalQuantity += quantity;
            // 失效条目即使勾选也不计入结算汇总
            if (Boolean.FALSE.equals(itemVO.getInvalid()) && isChecked(item)) {
                checkedQuantity += quantity;
                if (itemVO.getSubtotal() != null) {
                    checkedAmount = checkedAmount.add(itemVO.getSubtotal());
                }
            }
        }
        cartVO.setTotalQuantity(totalQuantity);
        cartVO.setCheckedQuantity(checkedQuantity);
        cartVO.setCheckedAmount(checkedAmount);
        return cartVO;
    }

    @Override
    public void updateQuantity(Long userId, Long id, CartQuantityRequest request) {
        validateQuantity(request.getQuantity());
        CartItem item = requireOwnedItem(userId, id);

        // 与加购保持一致的校验强度：失效 SKU / 下架商品不允许改数量
        ProductSku sku = requireOnSaleSku(item.getSkuId());
        if (sku.getStock() != null && request.getQuantity() > sku.getStock()) {
            throw new BusinessException(ResultCode.CART_STOCK_INSUFFICIENT);
        }
        item.setQuantity(request.getQuantity());
        cartItemMapper.updateById(item);
        syncCartField(userId, item);
    }

    @Override
    public void updateChecked(Long userId, Long id, CartCheckedRequest request) {
        CartItem item = requireOwnedItem(userId, id);
        item.setChecked(Boolean.TRUE.equals(request.getChecked()) ? 1 : 0);
        cartItemMapper.updateById(item);
        syncCartField(userId, item);
    }

    @Override
    public void updateAllChecked(Long userId, CartCheckedRequest request) {
        boolean checked = Boolean.TRUE.equals(request.getChecked());
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
        if (items.isEmpty()) {
            return;
        }

        List<CartItem> targetItems;
        if (checked) {
            // 全选只作用于有效条目，失效条目不参与勾选（仍保留在列表中由用户自行删除）
            Map<Long, ProductSku> skuMap = collectSkus(items);
            Map<Long, Product> productMap = collectProducts(skuMap.values());
            targetItems = items.stream()
                    .filter(item -> isItemValid(item, skuMap, productMap))
                    .toList();
        } else {
            targetItems = items;
        }
        if (targetItems.isEmpty()) {
            return;
        }
        cartItemMapper.update(null, new LambdaUpdateWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .in(CartItem::getId, targetItems.stream().map(CartItem::getId).toList())
                .set(CartItem::getChecked, checked ? 1 : 0));
        // DB 批量更新成功后，按统一的新勾选状态同步各条目的缓存 field
        Map<String, String> cache = new HashMap<>(targetItems.size());
        for (CartItem item : targetItems) {
            item.setChecked(checked ? 1 : 0);
            cache.put(String.valueOf(item.getSkuId()), cartFieldValue(item));
        }
        syncCartFields(userId, cache);
    }

    @Override
    public void delete(Long userId, Long id) {
        CartItem item = requireOwnedItem(userId, id);
        cartItemMapper.deleteById(id);
        removeCartField(userId, item.getSkuId());
    }

    @Override
    public void deleteChecked(Long userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getChecked, 1));
        // 条目级 hdel 需额外查询已勾选 skuId，直接整 key 失效，下次读回源全量重建
        evictCartCache(userId);
    }

    @Override
    public void clear(Long userId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
        evictCartCache(userId);
    }

    /**
     * 校验数量合法性，防止绕过 DTO 校验的直接调用传入非法值
     */
    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ResultCode.CART_QUANTITY_INVALID);
        }
    }

    private CartItem findItem(Long userId, Long skuId) {
        return cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, skuId));
    }

    /**
     * 取出属于当前用户的条目，不存在或归属他人一律视为不存在，避免越权操作他人购物车
     */
    private CartItem requireOwnedItem(Long userId, Long id) {
        CartItem item = cartItemMapper.selectById(id);
        if (item == null || !Objects.equals(item.getUserId(), userId)) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_FOUND);
        }
        return item;
    }

    /**
     * 校验 SKU 处于可购买状态：SKU 存在（2011）、规格在售（3004）、所属商品在售（2014），返回 SKU
     */
    private ProductSku requireOnSaleSku(Long skuId) {
        ProductSku sku = productSkuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException(ResultCode.PRODUCT_SKU_NOT_FOUND);
        }
        if (!Objects.equals(sku.getStatus(), 1)) {
            throw new BusinessException(ResultCode.CART_SKU_DISABLED);
        }
        Product product = productMapper.selectById(sku.getProductId());
        if (product == null || !Objects.equals(product.getStatus(), 1)) {
            throw new BusinessException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return sku;
    }

    private boolean isChecked(CartItem item) {
        return item.getChecked() != null && item.getChecked() == 1;
    }

    /**
     * 判断条目是否有效：SKU 存在且在售、所属商品存在且在售
     */
    private boolean isItemValid(CartItem item, Map<Long, ProductSku> skuMap, Map<Long, Product> productMap) {
        ProductSku sku = skuMap.get(item.getSkuId());
        if (sku == null || !Objects.equals(sku.getStatus(), 1)) {
            return false;
        }
        Product product = productMap.get(sku.getProductId());
        return product != null && Objects.equals(product.getStatus(), 1);
    }

    private Map<Long, ProductSku> collectSkus(List<CartItem> items) {
        Set<Long> skuIds = items.stream()
                .map(CartItem::getSkuId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductSku> skus = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                .in(ProductSku::getId, skuIds));
        return skus.stream().collect(Collectors.toMap(ProductSku::getId, sku -> sku));
    }

    private Map<Long, Product> collectProducts(Collection<ProductSku> skus) {
        Set<Long> productIds = skus.stream()
                .map(ProductSku::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .in(Product::getId, productIds));
        return products.stream().collect(Collectors.toMap(Product::getId, product -> product));
    }

    private CartItemVO buildItemVO(CartItem item, Map<Long, ProductSku> skuMap, Map<Long, Product> productMap) {
        CartItemVO vo = new CartItemVO();
        vo.setId(item.getId());
        vo.setSkuId(item.getSkuId());
        vo.setQuantity(item.getQuantity());
        vo.setChecked(isChecked(item));
        vo.setInvalid(false);

        ProductSku sku = skuMap.get(item.getSkuId());
        if (sku == null) {
            markInvalid(vo, REASON_SKU_LOST);
            return vo;
        }
        Product product = productMap.get(sku.getProductId());
        vo.setProductId(sku.getProductId());
        vo.setSpecs(sku.getSpecs());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        vo.setStock(sku.getStock());
        if (product != null) {
            vo.setProductName(product.getName());
            vo.setProductImage(product.getMainImage());
        }

        if (!Objects.equals(sku.getStatus(), 1)) {
            markInvalid(vo, REASON_SKU_DISABLED);
            return vo;
        }
        if (product == null || !Objects.equals(product.getStatus(), 1)) {
            markInvalid(vo, REASON_PRODUCT_OFF_SHELF);
            return vo;
        }
        if (sku.getPrice() != null && item.getQuantity() != null) {
            vo.setSubtotal(sku.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return vo;
    }

    private void markInvalid(CartItemVO vo, String reason) {
        vo.setInvalid(true);
        vo.setInvalidReason(reason);
    }
}
